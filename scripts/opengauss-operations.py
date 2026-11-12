"""Paired private backup, new-database restore and minimal operational checks.

No command overwrites or drops an existing database. Reports contain counts and
digests; database dumps and attachment manifests stay under ignored data/.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
IDENTIFIER = re.compile(r"[a-z_][a-z0-9_]{0,62}\Z")
RECOVERY = re.compile(r"dormlink_recovery_[a-z0-9_]+_test\Z")


def identifier(value: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise ValueError("Invalid database identifier")
    return '"' + value + '"'


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def private_directory(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=False)
    if os.name == "nt":
        user = subprocess.check_output(["whoami"], text=True).strip()
        result = subprocess.run(["icacls", str(path), "/inheritance:r", "/grant:r", user + ":(OI)(CI)F"], capture_output=True)
        if result.returncode:
            raise RuntimeError("Cannot restrict private backup directory permissions")
    else:
        path.chmod(0o700)


def settings() -> dict[str, str]:
    result = {}
    for line in (ROOT / "deploy/.env.opengauss").read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip().strip('"').strip("'")
    for key in ("OG_HOST", "OG_DATABASE", "OG_USER", "SSH_HOST", "SSH_USER"):
        if not result.get(key):
            raise ValueError("Missing local setting " + key)
    identifier(result["OG_DATABASE"])
    identifier(result["OG_USER"])
    return result


class DatabaseHost:
    def __init__(self, config: dict[str, str]):
        try:
            import paramiko
        except ImportError:
            # Reuse the already pinned local transport when available.
            sys.path.insert(0, str(ROOT / ".tools/ssh-python"))
            import paramiko
        self.config = config
        self.client = paramiko.SSHClient()
        self.client.load_host_keys(str(Path.home() / ".ssh/known_hosts"))
        self.client.set_missing_host_key_policy(paramiko.RejectPolicy())
        self.client.connect(config["SSH_HOST"], port=int(config.get("SSH_PORT", "22")),
                            username=config["SSH_USER"], password=config.get("SSH_PASSWORD"),
                            key_filename=config.get("SSH_KEY_PATH"), allow_agent=False,
                            look_for_keys=False, timeout=10)
        self.container = config.get("OG_CONTAINER", "OpenGauss")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", self.container):
            raise ValueError("Invalid database container")

    def close(self):
        self.client.close()

    def remote(self, command: str, timeout=60) -> str:
        stdin, stdout, stderr = self.client.exec_command(command, timeout=timeout)
        stdin.close()
        output, error = stdout.read(), stderr.read()
        if stdout.channel.recv_exit_status():
            # SQL/dump errors may contain private data; do not copy them into logs.
            raise RuntimeError("Remote database operation failed; inspect it locally with authorized access")
        return output.decode("utf-8").strip()

    def command(self, arguments: list[str], interactive=False) -> str:
        inner = "source /home/omm/.bashrc; " + shlex.join(arguments)
        return "docker exec " + ("-i " if interactive else "") + "-u omm " + shlex.quote(self.container) + " bash -c " + shlex.quote(inner)

    def sql(self, database: str, sql: str) -> list[list[str]]:
        identifier(database)
        output = self.remote(self.command(["gsql", "-p", "5432", "-d", database,
                                          "-qAt", "-v", "ON_ERROR_STOP=1", "-c", sql]))
        return [line.split("|") for line in output.splitlines() if line]

    def allow_application_host(self, database: str):
        """Add only this database/role and this developer host, retaining all reject rules."""
        identifier(database)
        role = self.config["OG_USER"]
        identifier(role)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect((self.config["OG_HOST"], int(self.config.get("OG_PORT", "15432"))))
            address = str(ipaddress.IPv4Address(route.getsockname()[0]))
        rule = "host " + database + " " + role + " " + address + "/32 md5"
        path = "/var/lib/opengauss/data/pg_hba.conf"
        old = self.remote("docker exec " + shlex.quote(self.container) + " cat " + shlex.quote(path))
        if rule not in old.splitlines():
            suffix = ".dormlink-" + uuid.uuid4().hex
            script = ('source /home/omm/.bashrc; set -e; cp -- "$2" "$2$3.backup"; '
                      '{ printf "%s\\n" "$1"; cat -- "$2"; } > "$2$3.new"; '
                      'chmod 600 "$2$3.new"; mv -- "$2$3.new" "$2"; gs_ctl reload -D /var/lib/opengauss/data')
            self.remote("docker exec -u omm " + shlex.quote(self.container) + " bash -c " + shlex.quote(script)
                        + " bash " + shlex.quote(rule) + " " + shlex.quote(path) + " " + shlex.quote(suffix))

    @contextmanager
    def frozen_writes(self, database: str, tables: list[str]):
        """SHARE permits gs_dump reads while blocking INSERT/UPDATE/DELETE briefly."""
        stdin, stdout, stderr = self.client.exec_command(self.command(
            ["gsql", "-p", "5432", "-d", database, "-qAt", "-v", "ON_ERROR_STOP=1"], True), timeout=45)
        try:
            names = ",".join("public." + identifier(t) for t in tables)
            stdin.write("BEGIN; SET LOCAL lockwait_timeout=5000; LOCK TABLE " + names
                        + " IN SHARE MODE; SELECT 'DORMLINK_BACKUP_LOCK_READY';\n")
            stdin.flush()
            while True:
                line = stdout.readline()
                if not line:
                    raise RuntimeError("Could not acquire a bounded, consistent backup lock")
                if line.strip() == "DORMLINK_BACKUP_LOCK_READY":
                    break
            yield
        finally:
            try:
                stdin.write("ROLLBACK;\n\\q\n")
                stdin.flush()
            except (OSError, EOFError):
                pass
            stdin.close()
            stdout.channel.close()

    def download_dump(self, database: str, target: Path):
        name = "/tmp/dormlink-paired-" + uuid.uuid4().hex + ".dump"
        host_file = name + ".host"
        try:
            self.remote(self.command(["gs_dump", "-p", "5432", "-Fc", "-f", name, database]))
            self.remote("umask 077; docker cp " + shlex.quote(self.container + ":" + name) + " " + shlex.quote(host_file)
                        + " && chmod 600 " + shlex.quote(host_file))
            with self.client.open_sftp() as sftp:
                sftp.get(host_file, str(target))
        finally:
            self.remote("rm -f -- " + shlex.quote(host_file))
            self.remote(self.command(["rm", "-f", "--", name]))

    def upload_restore(self, database: str, dump: Path):
        name = "/tmp/dormlink-restore-" + uuid.uuid4().hex + ".dump"
        try:
            with self.client.open_sftp() as sftp:
                sftp.put(str(dump), name)
                sftp.chmod(name, 0o600)
            self.remote("docker cp " + shlex.quote(name) + " " + shlex.quote(self.container + ":" + name)
                        + " && docker exec " + shlex.quote(self.container) + " chown omm " + shlex.quote(name))
            # Preserve original project-role ownership, not the restore operator's ownership.
            self.remote(self.command(["gs_restore", "-p", "5432", "-d", database, "-e", name]), timeout=120)
        finally:
            self.remote("rm -f -- " + shlex.quote(name))
            self.remote("docker exec " + shlex.quote(self.container) + " rm -f -- " + shlex.quote(name))


def tables(host: DatabaseHost, database: str) -> list[str]:
    return [r[0] for r in host.sql(database, "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")]


def snapshot(host: DatabaseHost, database: str, names: list[str]) -> dict:
    counts = {name: int(host.sql(database, "SELECT COUNT(*) FROM public." + identifier(name))[0][0]) for name in names}
    repairs = host.sql(database, "SELECT id,status,version,room_id,COALESCE(handler_user_id::text,'') FROM repair_order ORDER BY id")
    events = host.sql(database, "SELECT id,order_id,COALESCE(from_status,''),to_status,action,created_at FROM repair_event ORDER BY id")
    assets = host.sql(database, "SELECT id,storage_key,size_bytes,state,COALESCE(business_type,''),COALESCE(business_id::text,''),COALESCE(repair_event_id::text,'') FROM file_asset WHERE state<>'DELETED' ORDER BY id")
    checksum = lambda rows: hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()
    return {"tableCounts": counts, "repairDigest": checksum(repairs), "eventDigest": checksum(events),
            "assetDigest": checksum(assets), "assets": assets}


def uploads_root(config: dict[str, str]) -> Path:
    value = config.get("FILE_STORAGE", "data/uploads")
    path = Path(value)
    if not path.is_absolute():
        path = ROOT / "backend" / path
    path = path.resolve(strict=True)
    if not path.is_dir() or path.is_symlink():
        raise ValueError("Configured attachment directory is not usable")
    return path


def backup(host: DatabaseHost, config: dict[str, str]) -> dict:
    database = config["OG_DATABASE"]
    name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8]
    parent = ROOT / "data/backups"
    parent.mkdir(parents=True, exist_ok=True)
    folder = parent / name
    private_directory(folder)
    files = folder / "uploads"
    files.mkdir()
    source = uploads_root(config)
    names = tables(host, database)
    with host.frozen_writes(database, names):
        state = snapshot(host, database, names)
        copied = {}
        for row in state["assets"]:
            key, size = row[1], int(row[2])
            if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", key) or key in (".", ".."):
                raise ValueError("Unsafe attachment storage key")
            original = source / key
            if not original.is_file() or original.is_symlink() or original.stat().st_size != size:
                raise RuntimeError("A live attachment is absent or has an unexpected size")
            for item in (original, source / (key + ".thumb.jpg")):
                if item.is_file():
                    if item.is_symlink():
                        raise ValueError("Attachment symlinks are not copied")
                    target = files / item.name
                    shutil.copyfile(item, target)
                    copied[item.name] = {"bytes": target.stat().st_size, "sha256": digest(target)}
        host.download_dump(database, folder / "database.dump")
    manifest = {"formatVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
                "sourceDatabase": database, "consistentWriteLock": True, "databaseSha256": digest(folder / "database.dump"),
                "snapshot": state, "files": copied}
    (folder / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"operation": "backup", "passed": True, "backupDirectory": str(folder.relative_to(ROOT)),
            "databaseSha256": manifest["databaseSha256"], "tableCount": len(names),
            "repairCount": state["tableCounts"]["repair_order"], "assetCount": len(state["assets"]),
            "copiedFileCount": len(copied), "writeLockReleased": True}


def restore(host: DatabaseHost, config: dict[str, str], folder: Path, database: str) -> dict:
    if not RECOVERY.fullmatch(database) or database == config["OG_DATABASE"]:
        raise ValueError("Restore only to a new dormlink_recovery_*_test database")
    folder = folder.resolve(strict=True)
    if not folder.is_relative_to((ROOT / "data/backups").resolve()) or folder.is_symlink():
        raise ValueError("Use a private paired backup under data/backups")
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("formatVersion") != 1 or not manifest.get("consistentWriteLock"):
        raise ValueError("A compatible, paired backup manifest is required")
    if digest(folder / "database.dump") != manifest["databaseSha256"]:
        raise ValueError("Database dump checksum mismatch")
    for key, metadata in manifest["files"].items():
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,110}", key) or key in (".", ".."):
            raise ValueError("Unsafe manifest attachment key")
        path = folder / "uploads" / key
        if path.is_symlink() or path.stat().st_size != metadata["bytes"] or digest(path) != metadata["sha256"]:
            raise ValueError("Attachment checksum mismatch")
    existing = host.sql("postgres", "SELECT datname FROM pg_database WHERE datname='" + database + "'")
    if existing:
        raise ValueError("Restore target exists; refusing to overwrite it")
    target = ROOT / "data/recovery" / database
    target.parent.mkdir(parents=True, exist_ok=True)
    private_directory(target)
    host.sql("postgres", "CREATE DATABASE " + identifier(database) + " WITH OWNER "
             + identifier(config["OG_USER"]) + " DBCOMPATIBILITY 'PG'")
    host.allow_application_host(database)
    host.upload_restore(database, folder / "database.dump")
    restored = snapshot(host, database, tables(host, database))
    if restored != manifest["snapshot"]:
        raise RuntimeError("Restored business rows differ from the backup manifest")
    shutil.copytree(folder / "uploads", target / "uploads")
    for key, metadata in manifest["files"].items():
        if digest(target / "uploads" / key) != metadata["sha256"]:
            raise RuntimeError("Restored attachment cannot be read correctly")
    # A recovery environment must not accept sessions copied from the live service.
    host.sql(database, "UPDATE auth_session SET revoked_at=CURRENT_TIMESTAMP,last_seen_at=NULL WHERE revoked_at IS NULL")
    return {"operation": "restore", "passed": True, "targetDatabase": database,
            "targetUploads": str((target / "uploads").relative_to(ROOT)),
            "tableCountsMatch": True, "repairAndProgressDigestsMatch": True,
            "attachmentMetadataMatch": True, "attachmentHashesMatch": True,
            "restoredSessionsRevoked": True, "repairCount": restored["tableCounts"]["repair_order"],
            "assetCount": len(restored["assets"]), "copiedFileCount": len(manifest["files"]),
            "liveDatabaseOverwritten": False}


def check(host: DatabaseHost, config: dict[str, str]) -> dict:
    state = host.sql(config["OG_DATABASE"], "SELECT COUNT(*) FROM sys_user")
    files = uploads_root(config)
    probe = files / (".health-" + uuid.uuid4().hex)
    try:
        probe.write_bytes(b"dormlink-storage-check")
        readable = probe.read_bytes() == b"dormlink-storage-check"
    finally:
        probe.unlink(missing_ok=True)
    free = shutil.disk_usage(files).free
    pending = failed = 0
    names = tables(host, config["OG_DATABASE"])
    if "service_message" in names:
        pending = int(host.sql(config["OG_DATABASE"], "SELECT COUNT(*) FROM service_message WHERE delivery_state='PENDING'")[0][0])
        failed = int(host.sql(config["OG_DATABASE"], "SELECT COUNT(*) FROM service_message WHERE delivery_state='FAILED'")[0][0])
    planner = host.sql(config["OG_DATABASE"], "SELECT CASE WHEN dirty=TRUE THEN EXTRACT(EPOCH FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC')-updated_at)) ELSE 0 END FROM dispatch_revision WHERE id=1")
    stalled = bool(planner) and float(planner[0][0]) > 300
    return {"operation": "check", "passed": bool(state) and readable and free >= 64 * 1024 * 1024 and failed == 0 and not stalled,
            "database": "UP", "attachmentsReadWrite": readable,
            "diskAbove64MiB": free >= 64 * 1024 * 1024, "deliveryPending": pending,
            "deliveryFailed": failed, "serviceMessagesInstalled": "service_message" in names,
            "plannerDirtyForOver5Minutes": stalled}


def prepare_tests(host: DatabaseHost, config: dict[str, str]) -> dict:
    created = []
    for name in ("dormlink_account_test", "dormlink_followup_test", "dormlink_reliability_test"):
        if not host.sql("postgres", "SELECT datname FROM pg_database WHERE datname='" + name + "'"):
            host.sql("postgres", "CREATE DATABASE " + identifier(name) + " WITH OWNER "
                     + identifier(config["OG_USER"]) + " DBCOMPATIBILITY 'PG'")
            created.append(name)
        host.sql(name, "ALTER SCHEMA public OWNER TO " + identifier(config["OG_USER"]))
        host.sql(name, "GRANT ALL ON SCHEMA public TO " + identifier(config["OG_USER"]))
        host.allow_application_host(name)
    return {"operation": "prepare-tests", "passed": True, "created": created}


def self_test():
    class NoRemoteOperations:
        def __getattr__(self, _name):
            raise AssertionError("Unsafe restore reached a remote operation")

    for name in ("dormlink", "postgres", "dormlink_recovery_bad;DROP_test", "../outside"):
        try:
            restore(NoRemoteOperations(), {"OG_DATABASE": "dormlink"}, Path("unused"), name)
            raise AssertionError("Unsafe recovery target accepted")
        except ValueError:
            pass
    if not RECOVERY.fullmatch("dormlink_recovery_20261112_test"):
        raise AssertionError("Isolated target incorrectly rejected")
    for name in ("bad;drop", "../unsafe", "with space"):
        try:
            identifier(name)
            raise AssertionError("Unsafe SQL identifier accepted")
        except ValueError:
            pass
    print("Recovery target and SQL identifier safeguards passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("check", "backup", "restore", "prepare-tests", "self-test"))
    parser.add_argument("--backup-directory", type=Path)
    parser.add_argument("--database")
    parser.add_argument("--report", type=Path, default=ROOT / "output/acceptance/operations-result.json")
    args = parser.parse_args()
    if args.operation == "self-test":
        self_test()
        return
    config = settings()
    host = DatabaseHost(config)
    try:
        if args.operation == "backup":
            report = backup(host, config)
        elif args.operation == "restore":
            if not args.backup_directory or not args.database:
                raise ValueError("Restore requires --backup-directory and --database")
            report = restore(host, config, args.backup_directory, args.database)
        elif args.operation == "prepare-tests":
            report = prepare_tests(host, config)
        else:
            report = check(host, config)
        report["verifiedAt"] = datetime.now(timezone.utc).isoformat()
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        if not report["passed"]:
            raise SystemExit(2)
    finally:
        host.close()


if __name__ == "__main__":
    main()
