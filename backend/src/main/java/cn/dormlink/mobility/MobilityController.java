package cn.dormlink.mobility;

import static cn.dormlink.mobility.MobilityModels.*;

import cn.dormlink.identity.Actor;
import cn.dormlink.shared.api.ApiException;
import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.api.ApiResponses.Envelope;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/mobility")
public class MobilityController {
    private final MobilityService service;
    private final JsonMapper json;

    public MobilityController(MobilityService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping("/session")
    public Envelope<View> session(
            @AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        return ApiResponses.ok(service.view(actor), request);
    }

    @PostMapping("/sessions")
    public Envelope<View> start(
            @AuthenticationPrincipal Actor actor,
            @RequestBody Start body,
            HttpServletRequest request) {
        return ApiResponses.ok(service.start(actor, body), request);
    }

    @PostMapping("/sessions/{id}/{action:pause|resume|end}")
    public Envelope<View> action(
            @AuthenticationPrincipal Actor actor,
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody Action body,
            HttpServletRequest request) {
        return ApiResponses.ok(service.action(actor, id, action, body), request);
    }

    @PostMapping("/sessions/{id}/samples")
    public Envelope<Ack> samples(
            @AuthenticationPrincipal Actor actor,
            @PathVariable String id,
            HttpServletRequest request)
            throws java.io.IOException {
        if (request.getContentLengthLong() > POLICY.maxBodyBytes()) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "摘要正文不能超过16KB");
        }
        byte[] body = request.getInputStream().readNBytes(POLICY.maxBodyBytes() + 1);
        if (body.length > POLICY.maxBodyBytes()) {
            throw new ApiException(413, "PAYLOAD_TOO_LARGE", "摘要正文不能超过16KB");
        }
        Batch batch;
        try {
            batch = json.readValue(body, Batch.class);
        } catch (RuntimeException exception) {
            throw ApiException.bad("摘要JSON格式不正确");
        }
        return ApiResponses.ok(service.samples(actor, id, batch, body), request);
    }

    @PostMapping("/sessions/{id}/anchor-confirmations")
    public Envelope<Context> confirm(
            @AuthenticationPrincipal Actor actor,
            @PathVariable String id,
            @RequestBody Confirmation body,
            HttpServletRequest request) {
        return ApiResponses.ok(service.confirm(actor, id, body), request);
    }

    @GetMapping("/travel-models")
    public Envelope<TravelModels> models(
            @AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        return ApiResponses.ok(service.travelModels(actor), request);
    }

    @PostMapping("/travel-models/{fromId}/{toId}/rollback")
    public Envelope<TravelModel> rollback(
            @AuthenticationPrincipal Actor actor,
            @PathVariable String fromId,
            @PathVariable String toId,
            @RequestBody Rollback body,
            HttpServletRequest request) {
        return ApiResponses.ok(
                service.rollbackTravelModel(actor, fromId, toId, body.expectedVersion()), request);
    }

    @GetMapping("/anchors")
    public Envelope<Anchors> anchors(
            @AuthenticationPrincipal Actor actor, HttpServletRequest request) {
        return ApiResponses.ok(service.anchors(actor), request);
    }

    @PostMapping("/anchors")
    public Envelope<Anchor> create(
            @AuthenticationPrincipal Actor actor,
            @RequestBody AnchorWrite body,
            HttpServletRequest request) {
        return ApiResponses.ok(service.writeAnchor(actor, null, body), request);
    }

    @PatchMapping("/anchors/{id}")
    public Envelope<Anchor> update(
            @AuthenticationPrincipal Actor actor,
            @PathVariable String id,
            @RequestBody AnchorWrite body,
            HttpServletRequest request) {
        return ApiResponses.ok(service.writeAnchor(actor, id, body), request);
    }
}
