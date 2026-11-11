package cn.dormlink.housing;

import cn.dormlink.shared.application.BusinessSupport;
import cn.dormlink.shared.api.ApiResponses;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class HousingController {
  final HousingService housing;
  final BusinessSupport ops;

  HousingController(HousingService h, BusinessSupport o) {
    housing = h;
    ops = o;
  }

  Object command(Map<String, Object> b, HttpServletRequest r, Supplier<Object> w) {
    return ApiResponses.ok(
        ops.command(r.getMethod() + r.getRequestURI(), r.getHeader("Idempotency-Key"), b, w), r);
  }

  @GetMapping("/buildings")
  Object buildings(@RequestParam Map<String, String> q, HttpServletRequest r) {
    return ApiResponses.ok(housing.buildings(q), r);
  }

  @GetMapping("/buildings/{id}")
  Object building(@PathVariable String id, HttpServletRequest r) {
    return ApiResponses.ok(housing.building(id), r);
  }

  @PostMapping("/buildings")
  Object createBuilding(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.saveBuilding(null, b));
  }

  @PatchMapping("/buildings/{id}")
  Object editBuilding(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.saveBuilding(id, b));
  }

  @GetMapping("/rooms")
  Object rooms(@RequestParam Map<String, String> q, HttpServletRequest r) {
    return ApiResponses.ok(housing.rooms(q), r);
  }

  @GetMapping("/rooms/{id}")
  Object room(@PathVariable String id, HttpServletRequest r) {
    return ApiResponses.ok(housing.room(id), r);
  }

  @PostMapping("/rooms")
  Object createRoom(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.saveRoom(null, b));
  }

  @PatchMapping("/rooms/{id}")
  Object editRoom(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.saveRoom(id, b));
  }

  @GetMapping("/students")
  Object students(@RequestParam Map<String, String> q, HttpServletRequest r) {
    return ApiResponses.ok(housing.students(q), r);
  }

  @GetMapping("/students/{id}")
  Object student(@PathVariable String id, HttpServletRequest r) {
    return ApiResponses.ok(housing.student(id), r);
  }

  @PostMapping("/students")
  Object createStudent(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.saveStudent(null, b));
  }

  @PatchMapping("/students/{id}")
  Object editStudent(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.saveStudent(id, b));
  }

  @GetMapping("/residences")
  Object residences(@RequestParam Map<String, String> q, HttpServletRequest r) {
    return ApiResponses.ok(housing.residences(q, false), r);
  }

  @GetMapping("/me/residences")
  Object mine(@RequestParam Map<String, String> q, HttpServletRequest r) {
    return ApiResponses.ok(housing.residences(q, true), r);
  }

  @GetMapping("/me/room")
  Object current(HttpServletRequest r) {
    return ApiResponses.ok(housing.currentRoom(), r);
  }

  @PostMapping("/residences/check-in")
  Object checkIn(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.checkIn(b));
  }

  @PostMapping("/residences/{id}/transfer")
  Object transfer(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.move(id, b, false));
  }

  @PostMapping("/residences/{id}/check-out")
  Object checkout(
      @PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    return command(b, r, () -> housing.move(id, b, true));
  }
}
