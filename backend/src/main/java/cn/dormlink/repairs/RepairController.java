package cn.dormlink.repairs;

import cn.dormlink.media.FileService;
import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.application.BusinessSupport;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class RepairController {
    final RepairService repair;
    final FileService files;
    final BusinessSupport ops;

    RepairController(RepairService r, FileService f, BusinessSupport o) {
        repair = r;
        files = f;
        ops = o;
    }

    @GetMapping("/repairs")
    Object list(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(repair.list(q), r);
    }

    @GetMapping("/repairs/{id}")
    Object detail(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(repair.detail(id), r);
    }

    @GetMapping("/repairs/{id}/events")
    Object events(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(repair.events(id), r);
    }

    @PostMapping("/repairs")
    Object create(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return ApiResponses.ok(
                ops.command(
                        "POST/repairs", r.getHeader("Idempotency-Key"), b, () -> repair.create(b)),
                r);
    }

    @PatchMapping("/repairs/{id}/visit")
    Object visit(
            @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return ApiResponses.ok(
                ops.command(
                        r.getMethod() + r.getRequestURI(),
                        r.getHeader("Idempotency-Key"),
                        b,
                        () -> repair.visit(id, b)),
                r);
    }

    @PostMapping("/repairs/{id}/{action}")
    Object action(
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        return ApiResponses.ok(
                ops.command(
                        r.getRequestURI(),
                        r.getHeader("Idempotency-Key"),
                        b,
                        () -> repair.action(id, action, b)),
                r);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object upload(
            @RequestParam MultipartFile file, @RequestParam String usage, HttpServletRequest r)
            throws Exception {
        return ApiResponses.ok(files.upload(file, usage), r);
    }

    @GetMapping({"/files/{id}/content", "/files/{id}/thumbnail"})
    ResponseEntity<org.springframework.core.io.Resource> content(
            @PathVariable String id, HttpServletRequest request) throws Exception {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(files.content(id, request.getRequestURI().endsWith("/thumbnail")));
    }

    @DeleteMapping("/files/{id}")
    Object delete(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(files.delete(id), r);
    }
}
