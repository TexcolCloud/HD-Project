package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;
import static org.assertj.core.api.Assertions.*;
import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

class MobilityServiceTest {
    private final Instant now = Instant.parse("2026-11-11T01:00:00Z");
    private final Actor phone = new Actor("1","宿管","MANAGER",null,"csrf","10","MOBILE");
    private final Actor other = new Actor("1","宿管","MANAGER",null,"csrf","12","MOBILE");
    private MobilityService service;
    private SingleConnectionDataSource source;
    private final MutableClock clock=new MutableClock(now);
    static class MutableClock extends Clock {
        Instant current;
        MutableClock(Instant initial) { current=initial; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
    private JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();

    @BeforeEach
    void database() throws Exception {
        String configured=System.getenv("OPENGAUSS_TEST_URL");
        if (configured!=null && !configured.isBlank()) {
            String url=configured.replace("{suite}","mobility");
            if (!url.matches("jdbc:opengauss://[^/]+/dormlink_mobility_test(?:\\?.*)?")) {
                throw new IllegalArgumentException("Use the isolated dormlink_mobility_test database");
            }
            var properties=new java.util.Properties();
            properties.setProperty("user",System.getenv("OPENGAUSS_TEST_USER"));
            properties.setProperty("password",System.getenv("OPENGAUSS_TEST_PASSWORD"));
            properties.setProperty("stringtype","unspecified");
            properties.setProperty("use_boolean","true");
            source=new SingleConnectionDataSource(java.sql.DriverManager.getConnection(url,properties),true);
        } else {
            source = new SingleConnectionDataSource("jdbc:h2:mem:mobility_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        }
        var probe=new JdbcTemplate(source);
        probe.execute("SET TIME ZONE 'UTC'");
        boolean initialized;
        try { probe.queryForObject("SELECT COUNT(*) FROM field_work_session",Integer.class); initialized=true; }
        catch (org.springframework.jdbc.BadSqlGrammarException absent) { initialized=false; }
        if (!initialized) {
            String base=configured!=null && !configured.isBlank() ? "db/opengauss/" : "";
            String migrations=base.isEmpty() ? "db/migration/" : base;
            new ResourceDatabasePopulator(new ClassPathResource(base+"schema.sql"),
                new ClassPathResource(migrations+"V2__dynamic_dispatch.sql"),
                new ClassPathResource(migrations+"V4__mobility.sql")).execute(source);
        }
        jdbc = new JdbcTemplate(source);
        for (String table:java.util.List.of("mobility_model_revision","mobility_travel_model","travel_leg_summary",
                "mobility_pending_leg","mobility_batch_receipt","manager_context_snapshot","field_work_session","campus_anchor")) {
            jdbc.update("DELETE FROM "+table);
        }
        jdbc.update("DELETE FROM manager_building WHERE manager_user_id=1");
        jdbc.update("DELETE FROM auth_session WHERE user_id=1");
        jdbc.update("DELETE FROM sys_user WHERE id=1");
        jdbc.update("DELETE FROM building WHERE id IN (1,2)");
        jdbc.update("INSERT INTO sys_user(id,username,password_hash,display_name,role) VALUES(1,'manager','x','宿管','MANAGER')");
        jdbc.update("INSERT INTO building(id,code,name,floor_count) VALUES(1,'A','甲栋',6),(2,'B','乙栋',6)");
        jdbc.update("INSERT INTO manager_building VALUES(1,1),(1,2)");
        for (long session : new long[]{10,12}) {
            jdbc.update("INSERT INTO auth_session(id,user_id,token_hash,csrf_token,client_type,expires_at,created_at) "
                    + "VALUES(?,1,?,'csrf','MOBILE',TIMESTAMP '2027-01-01 00:00:00',TIMESTAMP '2026-01-01 00:00:00')",session,"hash-"+session);
        }
        service = new MobilityService(new MobilityRepository(jdbc),new DataSourceTransactionManager(source),
                json, clock);
    }

    @org.junit.jupiter.api.AfterEach
    void close() { if (source!=null) { source.destroy(); } }

    @Test
    void plannerProjectionKeepsGpsAndExpiredPositionBarriersInsteadOfRevivingAnOlderManualLocation() {
        service.writeAnchor(phone,null,new AnchorWrite("2","乙栋入口",1,31.0,121.0,50.0,true,null));
        View started=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        Sample fix=new Sample(1,"stream-1",now,0,31.0,121.0,5.0,null,null,null,"UNKNOWN","LOW");
        Batch batch=new Batch(started.session().sourceEpoch(),"gps-barrier",java.util.List.of(fix));
        service.samples(phone,started.session().id(),batch,json.writeValueAsBytes(batch));
        PlannerContext inferred=service.plannerContexts(now).get("1");
        assertThat(inferred).isNotNull();
        assertThat(inferred.buildingId()).isEqualTo("2");
        assertThat(inferred.floorNo()).isNull();
        clock.current=now.plusSeconds(121);
        service.cleanup();
        PlannerContext expired=service.plannerContexts(clock.instant()).get("1");
        assertThat(expired).isNotNull();
        assertThat(expired.buildingId()).isNull();
        assertThat(expired.floorNo()).isNull();
        assertThat(expired.observedAt()).isEqualTo(now);
    }

    @Test
    void delayedSamplesCannotKeepTheWorkingLeaseAlive() {
        View started=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        for (int receivedSeconds:new int[]{15,90}) {
            clock.current=now.plusSeconds(receivedSeconds);
            Sample delayed=new Sample(receivedSeconds,"delayed-stream",clock.instant().minusSeconds(90),
                    receivedSeconds*1000.0,31.0,121.0,5.0,null,null,null,"UNKNOWN","LOW");
            Batch batch=new Batch(started.session().sourceEpoch(),"delayed-"+receivedSeconds,java.util.List.of(delayed));
            Ack ack=service.samples(phone,started.session().id(),batch,json.writeValueAsBytes(batch));
            assertThat(ack.acceptedCount()).isEqualTo(1);
            assertThat(service.view(phone).session().leaseExpiresAt()).isEqualTo(started.session().leaseExpiresAt());
        }
        clock.current=now.plusSeconds(121);
        assertThat(service.activeUserIds(clock.instant())).isEmpty();
        assertThat(service.view(phone).session().state()).isEqualTo("EXPIRED");
        assertThat(service.view(phone).context().latitude()).isNull();
    }

    @Test
    void freshSamplesRenewTheLeaseButLargeFutureClockSkewDoesNot() {
        View started=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        clock.current=now.plusSeconds(90);
        Sample fresh=new Sample(1,"stream-1",clock.instant(),0,31.0,121.0,5.0,null,null,null,"UNKNOWN","LOW");
        Batch freshBatch=new Batch(started.session().sourceEpoch(),"fresh",java.util.List.of(fresh));
        service.samples(phone,started.session().id(),freshBatch,json.writeValueAsBytes(freshBatch));
        assertThat(service.view(phone).session().leaseExpiresAt()).isEqualTo(now.plusSeconds(210));
        clock.current=now.plusSeconds(180);
        Sample future=new Sample(2,"stream-1",clock.instant().plusSeconds(20),110000,31.0,121.0,5.0,null,null,null,"UNKNOWN","LOW");
        Batch futureBatch=new Batch(started.session().sourceEpoch(),"future",java.util.List.of(future));
        assertThat(service.samples(phone,started.session().id(),futureBatch,json.writeValueAsBytes(futureBatch)).acceptedCount()).isEqualTo(1);
        assertThat(service.view(phone).session().leaseExpiresAt()).isEqualTo(now.plusSeconds(210));
        clock.current=now.plusSeconds(211);
        assertThat(service.activeUserIds(clock.instant())).isEmpty();
    }

    @Test
    void staleDataDegradesAtSixtySecondsAndLeaseExpiryClearsPreciseCoordinates() {
        View view=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        Sample fix=new Sample(1,"stream-1",now,0,31.0,121.0,5.0,1.2,0.2,2,"WALKING","HIGH");
        Batch batch=new Batch(view.session().sourceEpoch(),"fix",java.util.List.of(fix));
        service.samples(phone,view.session().id(),batch,json.writeValueAsBytes(batch));
        clock.current=now.plusSeconds(61);
        assertThat(service.view(phone).context().status()).isEqualTo("STALE");
        assertThat(service.view(phone).context().speedMps()).isNull();
        assertThat(service.activeUserIds(clock.instant())).containsExactly("1");
        clock.current=now.plusSeconds(121);
        assertThat(service.view(phone).context().latitude()).isNull();
        assertThat(service.view(phone).context().status()).isEqualTo("UNKNOWN");
        assertThat(service.view(phone).session().state()).isEqualTo("EXPIRED");
        assertThat(service.activeUserIds(clock.instant())).isEmpty();
    }

    @Test
    void pauseResumeResetsTheObservationBaselineAndRejectsCallbacksFromTheOldEpoch() {
        View active=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        View paused=service.action(phone,active.session().id(),"pause",new Action(active.session().sourceEpoch(),active.session().version()));
        View resumed=service.action(phone,active.session().id(),"resume",new Action(paused.session().sourceEpoch(),paused.session().version()));
        assertThat(resumed.session().sourceEpoch()).isGreaterThan(active.session().sourceEpoch());
        assertThat(resumed.context().status()).isEqualTo("UNKNOWN");
        Batch old=new Batch(active.session().sourceEpoch(),"old",java.util.List.of(
                new Sample(1,"old-stream",now,0,31.0,121.0,5.0,null,null,null,"UNKNOWN",null)));
        assertThatThrownBy(() -> service.samples(phone,active.session().id(),old,json.writeValueAsBytes(old)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).code()).isEqualTo("SOURCE_EXPIRED");
    }

    @Test
    void aFirstBatchCannotMixTwoCollectionProcessesWithinTheSameEpoch() {
        View view=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        var one=new Sample(1,"stream-one",now.minusSeconds(1),0,30.0,120.0,5.0,null,null,null,"UNKNOWN","HIGH");
        var two=new Sample(2,"stream-two",now,1000,30.01,120.01,5.0,null,null,null,"UNKNOWN","HIGH");
        var batch=new Batch(view.session().sourceEpoch(),"mixed",java.util.List.of(one,two));
        assertThatThrownBy(() -> service.samples(phone,view.session().id(),batch,json.writeValueAsBytes(batch)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).code()).isEqualTo("SOURCE_RESTART_REQUIRED");
        assertThat(service.view(phone).context().latitude()).isNull();
    }

    @Test
    void aReliableUniqueConfiguredEntranceCanSuggestABuildingButNeverAFloor() {
        service.writeAnchor(phone,null,new AnchorWrite("2","乙栋入口",1,31.0,121.0,50.0,true,null));
        View view=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        Sample fix=new Sample(1,"stream-1",now,0,31.00001,121.00001,5.0,1.2,0.2,2,"WALKING","HIGH");
        Batch batch=new Batch(view.session().sourceEpoch(),"fix",java.util.List.of(fix));
        service.samples(phone,view.session().id(),batch,json.writeValueAsBytes(batch));
        assertThat(service.view(phone).context().buildingId()).isEqualTo("2");
        assertThat(service.view(phone).context().floorNo()).isNull();
        assertThat(service.view(phone).context().source()).isEqualTo("GPS");
    }

    @Test
    void aRetriedBatchReturnsTheSameShortAckAndNeverStoresCoordinatesInReceipts() {
        View view=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        Sample sample=new Sample(1,"stream-1",now,0,30.0,120.0,5.0,1.2,0.2,2,"WALKING","HIGH");
        Batch batch=new Batch(view.session().sourceEpoch(),"batch-1",java.util.List.of(sample));
        byte[] bytes=json.writeValueAsBytes(batch);
        Ack first=service.samples(phone,view.session().id(),batch,bytes);
        assertThat(first.acceptedCount()).isEqualTo(1);
        assertThat(first.context()).isNull();
        assertThat(service.samples(phone,view.session().id(),batch,bytes)).isEqualTo(first);
        assertThat(service.view(phone).context().speedMps()).isEqualTo(1.2);
        assertThat(service.view(phone).session().version()).isEqualTo(view.session().version());
        assertThatThrownBy(() -> service.samples(phone,view.session().id(),batch,"changed".getBytes()))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).status()).isEqualTo(409);
    }

    @Test
    void crossingToAnotherBuildingNeverCarriesThePreviousFloor() {
        var a=service.writeAnchor(phone,null,new AnchorWrite("1","甲栋三楼",3,30.0,120.0,20.0,true,null));
        View view=service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        Context confirmed=service.confirm(phone,view.session().id(),
                new Confirmation(view.session().sourceEpoch(),a.id(),"QR",view.context().contextVersion()));
        assertThat(confirmed.floorNo()).isEqualTo(3);
        Sample outside=new Sample(1,"stream-1",now.plusSeconds(1),1000,30.01,120.01,5.0,1.2,0.2,2,"WALKING","HIGH");
        Batch batch=new Batch(view.session().sourceEpoch(),"outside",java.util.List.of(outside));
        service.samples(phone,view.session().id(),batch,json.writeValueAsBytes(batch));
        assertThat(service.view(phone).context().buildingId()).isNull();
        assertThat(service.view(phone).context().floorNo()).isNull();
        assertThat(service.plannerContexts(now).get("1").buildingId()).isNull();
        assertThat(service.plannerContexts(now).get("1").floorNo()).isNull();
    }

    @Test
    void explicitTakeoverFencesThePreviousPhoneEvenWhenItsAuthenticationStillWorks() {
        View first = service.start(phone,new Start("phone-1","mobility-v1",false,false,null));
        assertThatThrownBy(() -> service.start(other,new Start("phone-2","mobility-v1",false,false,null)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).status()).isEqualTo(409);
        View replacement = service.start(other,new Start("phone-2","mobility-v1",false,true,first.session().version()));
        assertThat(replacement.session().sourceEpoch()).isGreaterThan(first.session().sourceEpoch());
        assertThatThrownBy(() -> service.confirm(phone,first.session().id(),
                new Confirmation(first.session().sourceEpoch(),"missing","MANUAL",0)))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).status()).isEqualTo(409);
        assertThat(service.activeUserIds(now)).containsExactly("1");
    }
}
