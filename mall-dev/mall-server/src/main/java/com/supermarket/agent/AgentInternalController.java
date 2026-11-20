package com.supermarket.agent;

import com.supermarket.dto.Response;
import com.supermarket.dto.ResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Dedicated service identity boundary; portal/admin browser credentials are not reused here. */
@RestController
@RequestMapping("/internal/agent")
public class AgentInternalController {
    private final AgentDataService data;
    private final AgentRuntimeService runtime;

    public AgentInternalController(AgentDataService data, AgentRuntimeService runtime) {
        this.data = data; this.runtime = runtime;
    }

    @GetMapping("/context")
    public ResponseDTO<?> context(HttpServletRequest request) { return Response.success(data.context(run(request))); }

    @GetMapping("/memory")
    public ResponseDTO<?> memory(HttpServletRequest request) { return Response.success(data.memory(run(request))); }

    @PostMapping("/memory")
    public ResponseDTO<?> saveMemory(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return Response.success(data.saveMemory(run(request), body));
    }

    @GetMapping("/conversations")
    public ResponseDTO<?> conversations(HttpServletRequest request, @RequestParam(defaultValue="1") int page,
                                        @RequestParam(defaultValue="10") int size) {
        return Response.success(data.conversations(run(request), page, size));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseDTO<?> conversation(HttpServletRequest request, @PathVariable long id,
                                      @RequestParam(name="before_id", required=false) Long beforeId,
                                      @RequestParam(defaultValue="10") int size) {
        return Response.success(data.conversation(run(request), id, beforeId, size));
    }

    @GetMapping("/orders")
    public ResponseDTO<?> orders(HttpServletRequest request, @RequestParam(required = false) Integer status,
                                 @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return Response.success(data.listOrders(run(request), status, page, size));
    }

    @GetMapping("/orders/{id}")
    public ResponseDTO<?> order(HttpServletRequest request, @PathVariable int id) { return Response.success(data.order(run(request), id)); }

    @GetMapping("/products")
    public ResponseDTO<?> products(HttpServletRequest request, @RequestParam String keyword,
                                   @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return Response.success(data.listProducts(run(request), keyword, page, size));
    }

    @GetMapping("/products/{id}")
    public ResponseDTO<?> product(HttpServletRequest request, @PathVariable int id) { return Response.success(data.product(run(request), id)); }

    @GetMapping("/tickets")
    public ResponseDTO<?> tickets(HttpServletRequest request, @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int size) {
        return Response.success(data.tickets(run(request), page, size));
    }

    @GetMapping("/tickets/{id}")
    public ResponseDTO<?> ticket(HttpServletRequest request, @PathVariable String id) { return Response.success(data.ticket(run(request), id)); }

    @PostMapping("/actions")
    public ResponseDTO<?> action(HttpServletRequest request, @RequestBody Map<String, Object> body) { return Response.success(data.action(run(request), body)); }

    @PostMapping("/human-tasks")
    public ResponseDTO<?> prepare(HttpServletRequest request, @RequestBody Map<String, Object> body) { return Response.success(runtime.prepareHuman(run(request), body)); }

    @PostMapping("/human-tasks/{id}/activate")
    public ResponseDTO<?> activate(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return Response.success(runtime.activateHuman(run(request), id, body));
    }

    @PostMapping("/runs/{id}/outcome")
    public ResponseDTO<?> outcome(HttpServletRequest request, @PathVariable String id, @RequestBody Map<String, Object> body) {
        return Response.success(runtime.outcome(run(request), id, body));
    }

    private AgentRun run(HttpServletRequest request) {
        if (!(request.getAttribute(AgentInternalAuthFilter.ATTRIBUTE) instanceof AgentRun run)) throw new AgentException(401, "缺少可信运行身份");
        return run;
    }
}
