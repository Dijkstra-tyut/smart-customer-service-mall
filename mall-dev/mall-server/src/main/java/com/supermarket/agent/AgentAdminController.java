package com.supermarket.agent;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.supermarket.dto.Response;
import com.supermarket.dto.ResponseDTO;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@SaCheckRole("admin")
@RestController
@RequestMapping("/admin/customer/tasks")
public class AgentAdminController {
    private final AgentRuntimeService runtime;
    public AgentAdminController(AgentRuntimeService runtime) { this.runtime = runtime; }

    @GetMapping
    public ResponseDTO<?> list(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return Response.success(runtime.tasks(status, page, size));
    }

    @GetMapping("/{id}")
    public ResponseDTO<?> task(@PathVariable String id) { return Response.success(runtime.task(id)); }

    @PostMapping("/{id}/decision")
    public ResponseDTO<?> decide(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Response.success(runtime.decide(id, body, StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/{id}/retry")
    public ResponseDTO<?> retry(@PathVariable String id) { return Response.success(runtime.retry(id)); }
}
