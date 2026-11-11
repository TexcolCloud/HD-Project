package cn.dormlink.reporting;

import cn.dormlink.shared.api.ApiResponses;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
class StatisticsController {
    final StatisticsService statistics;

    StatisticsController(StatisticsService s) {
        statistics = s;
    }

    @GetMapping("/statistics/overview")
    Object overview(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(statistics.overview(q), r);
    }

    @GetMapping("/statistics/repairs")
    Object repairs(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(statistics.repairs(q), r);
    }

    @GetMapping("/statistics/hygiene")
    Object hygiene(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(statistics.hygiene(q), r);
    }

    @GetMapping("/statistics/visitors")
    Object visitors(@RequestParam Map<String, String> q, HttpServletRequest r) {
        return ApiResponses.ok(statistics.visitors(q), r);
    }
}
