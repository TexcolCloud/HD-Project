package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;
import static cn.dormlink.mobility.MobilityRepository.*;

import cn.dormlink.identity.Actor;
import cn.dormlink.identity.SessionsRevoked;
import cn.dormlink.shared.api.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Owns work-source authorization, lease transitions, and terminal cleanup within the caller's
 * transaction.
 */
final class MobilityWorkSessions {
    private final MobilityRepository repository;
    private final Clock clock;

    MobilityWorkSessions(MobilityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    void authorize(Actor actor, boolean mobile) {
        actor.requireManager();
        if (mobile && !"MOBILE".equals(actor.clientType())) {
            throw new ApiException(403, "MOBILE_REQUIRED", "请在鸿蒙移动端开启现场辅助");
        }
        if (actor.sessionId() == null
                || !repository.validLogin(actor.sessionId(), actor.id(), clock.instant())) {
            throw new ApiException(401, "UNAUTHORIZED", "登录会话已失效");
        }
    }

    void expireCurrent(String userId) {
        Work work = repository.current(userId, true);
        if (work != null && !valid(work, clock.instant())) {
            invalidate(work, "EXPIRED");
        }
    }

    void start(Actor actor, Start request) {
        Instant now = clock.instant();
        Work current = repository.current(actor.id(), true);
        Work latest = repository.latest(actor.id());
        int epoch = latest == null ? 1 : latest.view().sourceEpoch() + 1;
        if (current != null && !valid(current, now)) {
            invalidate(current, "EXPIRED");
            current = null;
        }
        if (current != null) {
            if (!Boolean.TRUE.equals(request.takeover())) {
                if (actor.sessionId().equals(current.authSessionId())
                        && request.clientInstanceId().equals(current.clientInstanceId())
                        && "ACTIVE".equals(current.view().state())) {
                    return;
                }
                throw new ApiException(409, "SOURCE_IN_USE", "已有设备工作会话，请明确接管后开启");
            }
            if (request.expectedVersion() == null
                    || request.expectedVersion() != current.view().version()) {
                throw ApiException.conflict("工作会话已变化，请刷新后重试");
            }
            invalidate(current, "ENDED");
        }
        String workId = UUID.randomUUID().toString();
        repository.createWork(workId, actor, request, epoch, now);
        repository.attachContext(actor.id(), workId);
    }

    void action(Actor actor, String workId, String action, Action request) {
        Work work = owned(actor, workId, true);
        if (work.view().sourceEpoch() != request.sourceEpoch()
                || work.view().version() != request.expectedVersion()) {
            throw ApiException.conflict("工作会话来源或版本已变化");
        }
        if (!Set.of("ACTIVE", "PAUSED").contains(work.view().state())) {
            throw ApiException.conflict("工作会话已结束");
        }
        if ("end".equals(action)) {
            invalidate(work, "ENDED");
        } else if ("pause".equals(action) && "ACTIVE".equals(work.view().state())) {
            repository.pause(workId);
            repository.clearContext(actor.id(), workId);
        } else if ("resume".equals(action) && "PAUSED".equals(work.view().state())) {
            if (!valid(work, clock.instant()) || !actor.sessionId().equals(work.authSessionId())) {
                throw ApiException.conflict("来源租约已过期，请重新开启现场辅助");
            }
            repository.resume(workId, clock.instant().plusSeconds(POLICY.leaseSeconds()));
        } else {
            throw ApiException.conflict("不支持当前工作会话状态转换");
        }
    }

    Work source(Actor actor, String workId, int epoch) {
        Work work = owned(actor, workId, true);
        if (!"ACTIVE".equals(work.view().state())
                || epoch != work.view().sourceEpoch()
                || !actor.sessionId().equals(work.authSessionId())
                || !valid(work, clock.instant())) {
            throw new ApiException(409, "SOURCE_EXPIRED", "当前来源已被接管、暂停或过期，请刷新工作会话");
        }
        return work;
    }

    boolean valid(Work work, Instant now) {
        return work.view().leaseExpiresAt().isAfter(now)
                && repository.validLogin(work.authSessionId(), null, now);
    }

    void revoked(SessionsRevoked event) {
        repository
                .revokedWorks(event.userId(), event.sessionId())
                .forEach(work -> invalidate(work, "EXPIRED"));
    }

    void cleanup() {
        Instant now = clock.instant();
        for (String workId : repository.expiredWorkIds(now)) {
            // A renewal may commit after selection; recheck the locked row before expiring it.
            Work work = repository.work(workId, true);
            if (work != null
                    && Set.of("ACTIVE", "PAUSED").contains(work.view().state())
                    && !valid(work, now)) {
                invalidate(work, "EXPIRED");
            }
        }
        repository.purgeExpiredData(now);
    }

    private Work owned(Actor actor, String workId, boolean lock) {
        Work work = repository.work(workId, lock);
        if (work == null || !actor.id().equals(work.view().userId())) {
            throw ApiException.missing();
        }
        return work;
    }

    private void invalidate(Work work, String state) {
        repository.endWork(work.view().id(), state, clock.instant());
        repository.clearContext(work.view().userId(), work.view().id());
    }
}
