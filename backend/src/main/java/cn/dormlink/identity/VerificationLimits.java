package cn.dormlink.identity;

import cn.dormlink.shared.crypto.Digests;
import cn.dormlink.shared.jdbc.Db;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable budgets. Each guard serializes only the short transaction that charges its counters. */
@Component
class VerificationLimits {
    private final Db db;
    private final TransactionTemplate writes;

    VerificationLimits(Db db, PlatformTransactionManager transactions) {
        this.db = db;
        writes = new TransactionTemplate(transactions);
    }

    boolean issue(String source, String phone, String accountId, boolean graphic) {
        if (graphic) {
            var buckets =
                    new ArrayList<>(
                            List.of(
                                    new Limit("GRAPHIC_GLOBAL", 2000, 3600),
                                    new Limit("GRAPHIC_SOURCE:" + source, 180, 3600),
                                    new Limit("GRAPHIC_PHONE:" + phone, 60, 3600)));
            if (accountId != null) {
                buckets.add(new Limit("GRAPHIC_ACCOUNT:" + accountId, 60, 3600));
            }
            return charge("ISSUE", buckets);
        }
        var limits =
                new ArrayList<>(
                        List.of(
                                new Limit("SMS_GLOBAL", 300, 3600),
                                new Limit("SMS_SOURCE:" + source, 30, 3600),
                                new Limit("SMS_PHONE:" + phone, 8, 3600),
                                new Limit("SMS_RESEND:" + phone, 1, 60)));
        if (accountId != null) {
            limits.add(new Limit("SMS_ACCOUNT:" + accountId, 12, 3600));
        }
        return charge("ISSUE", limits);
    }

    boolean verify(String source, String reference, String phone, String accountId) {
        var limits =
                new ArrayList<>(
                        List.of(
                                new Limit("VERIFY_GLOBAL", 3000, 3600),
                                new Limit("VERIFY_SOURCE:" + source, 150, 3600),
                                new Limit("VERIFY_REFERENCE:" + reference, 5, 300)));
        if (phone != null) {
            limits.add(new Limit("VERIFY_PHONE:" + phone, 40, 3600));
        }
        if (accountId != null) {
            limits.add(new Limit("VERIFY_ACCOUNT:" + accountId, 40, 3600));
        }
        return charge("VERIFY", limits);
    }

    private boolean charge(String guard, List<Limit> limits) {
        return Boolean.TRUE.equals(
                writes.execute(
                        status -> {
                            db.one(
                                    "SELECT bucket_key FROM auth_verification_limit WHERE"
                                            + " bucket_key=? FOR UPDATE",
                                    guard);
                            Instant now = Instant.now();
                            var charges = new ArrayList<Charge>();
                            for (var limit : limits) {
                                String key = Digests.sha256(limit.key());
                                var row =
                                        db.find(
                                                "SELECT used_count,expires_at FROM"
                                                    + " auth_verification_limit WHERE bucket_key=?",
                                                key);
                                boolean active =
                                        row != null
                                                && Instant.parse(row.get("expiresAt").toString())
                                                        .isAfter(now);
                                long previous = active ? Db.number(row, "usedCount") : 0;
                                if (previous >= limit.maximum()) {
                                    return false;
                                }
                                Instant expiry =
                                        active
                                                ? Instant.parse(row.get("expiresAt").toString())
                                                : now.plusSeconds(limit.seconds());
                                charges.add(
                                        new Charge(
                                                key,
                                                previous + 1,
                                                Timestamp.from(expiry),
                                                row == null));
                            }
                            for (var charge : charges) {
                                if (charge.create()) {
                                    db.update(
                                            "INSERT INTO"
                                                + " auth_verification_limit(bucket_key,used_count,expires_at)"
                                                + " VALUES(?,?,?)",
                                            charge.key(),
                                            charge.count(),
                                            charge.expiresAt());
                                } else {
                                    db.update(
                                            "UPDATE auth_verification_limit SET"
                                                + " used_count=?,expires_at=? WHERE bucket_key=?",
                                            charge.count(),
                                            charge.expiresAt(),
                                            charge.key());
                                }
                            }
                            return true;
                        }));
    }

    @Scheduled(fixedDelay = 300_000)
    void dropExpired() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(3600));
        writes.executeWithoutResult(
                status -> {
                    db.one(
                            "SELECT bucket_key FROM auth_verification_limit WHERE"
                                    + " bucket_key='ISSUE' FOR UPDATE");
                    db.one(
                            "SELECT bucket_key FROM auth_verification_limit WHERE"
                                    + " bucket_key='VERIFY' FOR UPDATE");
                    db.update("DELETE FROM auth_verification_limit WHERE expires_at<?", cutoff);
                });
        db.update("DELETE FROM auth_phone_challenge WHERE expires_at<?", cutoff);
        db.update("DELETE FROM auth_password_reset WHERE expires_at<?", cutoff);
    }

    private record Limit(String key, int maximum, long seconds) {}

    private record Charge(String key, long count, Timestamp expiresAt, boolean create) {}
}
