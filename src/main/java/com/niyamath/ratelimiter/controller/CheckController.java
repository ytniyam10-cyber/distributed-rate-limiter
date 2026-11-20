package com.niyamath.ratelimiter.controller;

import com.niyamath.ratelimiter.models.CheckRequest;
import com.niyamath.ratelimiter.models.CheckResponse;
import com.niyamath.ratelimiter.service.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Rate Limiter", description = "Check request allowance using Sliding Window or Fixed Window strategies")
public class CheckController {

    private final RateLimiterService rateLimiterService;

    public CheckController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Operation(
            summary = "Evaluate rate limit for a client",
            description = "Atomically checks and updates request quota in Redis (default: 5 requests per 60 seconds)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed within quota"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded (Too Many Requests)")
    })
    @PostMapping("/check")
    public ResponseEntity<CheckResponse> check(@RequestBody CheckRequest request){
        CheckResponse response=rateLimiterService.check(request.clientId(),request.algorithm(), 5, 60);
        return new ResponseEntity<>(response,(response.allowed())?HttpStatus.OK:HttpStatus.TOO_MANY_REQUESTS);
    }
}
