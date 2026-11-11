package cn.dormlink.fieldservice;

import cn.dormlink.shared.api.ApiResponses;
import cn.dormlink.shared.application.BusinessSupport;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1")
class OperationsController {
    final OperationsService service;
    final BusinessSupport ops;

    OperationsController(OperationsService s, BusinessSupport o) {
        service = s;
        ops = o;
    }

    Object command(Map<String, Object> b, HttpServletRequest r, Supplier<Object> w) {
        return ApiResponses.ok(
                ops.command(
                        r.getMethod() + r.getRequestURI(), r.getHeader("Idempotency-Key"), b, w),
                r);
    }

    @GetMapping("/hygiene-inspections")
    Object hygiene(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(service.hygieneList(q, false), r);
    }

    @GetMapping("/me/hygiene-inspections")
    Object mine(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(service.hygieneList(q, true), r);
    }

    @GetMapping("/hygiene-inspections/{id}")
    Object detail(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(service.hygiene(id), r);
    }

    @GetMapping("/hygiene-inspections/{id}/history")
    Object history(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(service.hygieneHistory(id), r);
    }

    @PostMapping("/hygiene-inspections")
    Object create(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> service.saveHygiene(null, b));
    }

    @PatchMapping("/hygiene-inspections/{id}")
    Object edit(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> service.saveHygiene(id, b));
    }

    @PostMapping("/hygiene-inspections/{id}/void")
    Object cancel(
            @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> service.voidHygiene(id, b));
    }

    @GetMapping("/visitors")
    Object visitors(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(service.visitors(q), r);
    }

    @GetMapping("/visitors/{id}")
    Object visitor(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(service.visitor(id), r);
    }

    @PostMapping("/visitors")
    Object createVisitor(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> service.createVisitor(b));
    }

    @PostMapping("/visitors/{id}/leave")
    Object leave(
            @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> service.leave(id, b));
    }

    @PatchMapping("/visitors/{id}")
    Object visitorEdit(
            @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return command(b, r, () -> service.correctVisitor(id, b));
    }

    @PostMapping("/visitors/{id}/reveal-contact")
    Object reveal(@PathVariable String id, HttpServletRequest r) {
        return ApiResponses.ok(service.reveal(id), r);
    }
}
