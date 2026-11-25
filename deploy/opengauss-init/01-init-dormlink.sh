#!/usr/bin/env bash
set -euo pipefail
export GAUSSHOME=/usr/local/opengauss
export PATH="$GAUSSHOME/bin:$PATH" LD_LIBRARY_PATH="$GAUSSHOME/lib"
role=${OG_USER:-dormlink}
primary=${OG_DATABASE:-dormlink}
: "${OG_PASSWORD:?OG_PASSWORD is required}"

sql() {
    gsql -X -qAt -v ON_ERROR_STOP=1 -d "$1" "${@:2}"
}

if [[ ${1:-} == --health ]]; then
    # The entrypoint briefly starts a loopback-only server before exec'ing gaussdb.
    [[ $(cat /proc/1/comm) == gaussdb ]]
    [[ $(printf '%s\n' "$OG_PASSWORD" | sql "$primary" -h "$(hostname)" -U "$role" \
        -c "SELECT datcompatibility FROM pg_database WHERE datname=current_database()") == PG ]]
    exit
fi

if [[ ${1:-} == --reconcile ]]; then
    for ((attempt=0; attempt<120; attempt++)); do
        if [[ $(cat /proc/1/comm) == gaussdb ]]; then
            if response=$(sql postgres -c 'SELECT 1' 2>&1) || [[ $response == *'ALTER ROLE "omm" PASSWORD'* ]]; then break; fi
        fi
        sleep 1
    done
    [[ $(cat /proc/1/comm) == gaussdb ]] || { echo "Timed out waiting for openGauss startup" >&2; exit 1; }
fi

exec 9>"$PGDATA/.dormlink-provision.lock"
flock -w 120 9
# Some 6.0 clusters require the initial administrator to set its password on first SQL use.
if ! response=$(sql postgres -c 'SELECT 1' 2>&1); then
    [[ $response == *'ALTER ROLE "omm" PASSWORD'* ]] || { echo "Administrator SQL connection failed" >&2; exit 1; }
    admin=${GS_PASSWORD//\'/\'\'}
    printf '%s\n' "ALTER ROLE omm PASSWORD '$admin';" | sql postgres >/dev/null 2>&1 ||
        { echo "Cannot set the initial administrator password" >&2; exit 1; }
fi
for name in "$role" "$primary"; do
    [[ $name =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || { echo "Invalid application role or database name" >&2; exit 1; }
done
case "$primary" in postgres|template0|template1|dormlink_*_test) echo "Choose a separate application database" >&2; exit 1;; esac
if [[ $(sql postgres -c "SELECT count(*) FROM pg_roles WHERE rolname='$role'") == 0 ]]; then
    # Keep the password off the command line and out of SQL error diagnostics.
    secret=${OG_PASSWORD//\'/\'\'}
    printf '%s\n' "SET standard_conforming_strings=on;" \
        "CREATE USER \"$role\" WITH NOSYSADMIN NOCREATEDB NOCREATEROLE PASSWORD '$secret';" |
        sql postgres >/dev/null 2>&1 || { echo "Cannot create application role; verify password policy" >&2; exit 1; }
fi
[[ $(sql postgres -c "SELECT count(*) FROM pg_roles WHERE rolname='$role' AND rolcanlogin AND NOT (rolsuper OR rolsystemadmin OR rolcreatedb OR rolcreaterole OR rolreplication)") == 1 ]] ||
    { echo "Existing role does not meet application privilege requirements" >&2; exit 1; }

provision() {
    local db=$1 mode
    mode=$(sql postgres -c "SELECT datcompatibility || ':' || pg_encoding_to_char(encoding) FROM pg_database WHERE datname='$db'")
    if [[ -z $mode ]]; then
        sql postgres -c "CREATE DATABASE \"$db\" DBCOMPATIBILITY='PG' ENCODING='UTF8'"
    elif [[ $mode != PG:UTF8 ]]; then
        echo "Expected PG/UTF8 for $db; existing database preserved" >&2; return 1
    fi
    sql postgres -c "GRANT ALL PRIVILEGES ON DATABASE \"$db\" TO \"$role\""
    sql "$db" <<SQL
GRANT USAGE, CREATE ON SCHEMA public TO "$role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO "$role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO "$role";
SQL
}
provision "$primary"
for suite in platform dispatch ai concurrency refactor mobility mobility_http account followup reliability; do
    provision "dormlink_${suite}_test"
done
