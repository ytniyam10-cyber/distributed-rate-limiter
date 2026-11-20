# Distributed Rate Limiter

A Redis-backed rate limiting service built with Spring Boot. Other applications call one endpoint to ask "may this client make a request right now?" and get back an allowed or blocked answer. All state lives in Redis and is updated by atomic Lua scripts, so limits stay correct under heavy concurrency and across multiple app instances.

**Live demo:** [Swagger UI](https://distributed-rate-limiter-4mix.onrender.com/swagger-ui/index.html)

> The demo runs on a free Render instance that spins down when idle. The first request after a quiet period can take a while to respond.

## Highlights

- Two algorithms behind one interface: **fixed window** and **sliding window**
- Atomic enforcement with **Redis Lua scripts**
- Verified under load: exactly **5 of ~22,000** simultaneous requests were allowed for a single client (k6, 50 concurrent users)
- **~2,100 to 2,200 requests/s at p95 of about 27 ms** on a laptop (app, Redis and load generator on one machine)
- **Fails open** if Redis is unavailable (configurable)
- Dockerized, with a `docker-compose.yml` for local use and Swagger/OpenAPI docs

## Tech stack

Java 21, Spring Boot, Spring Data Redis (Lettuce), Redis 7, Lua, Maven, Docker, JUnit 5, k6, Swagger/OpenAPI.

## How it works

```
                    +------------------------------------------+
  Client app  --->  |  Spring Boot service                     |
  POST /api/v1/check|  Controller -> RateLimiterService        |
                    |                  |                       |
                    |      picks a strategy by name            |
                    |        /                     \           |
                    |  FixedWindowStrategy   SlidingWindowStrategy
                    +--------|----------------------|----------+
                             |   one Lua script call each
                             v                      v
                    +------------------------------------------+
                    |                  Redis                   |
                    |  ratelimit:fixed:{id}   (counter + TTL)  |
                    |  ratelimit:sliding:{id} (sorted set)     |
                    +------------------------------------------+
```

1. The controller receives `{clientId, algorithm}`.
2. `RateLimiterService` looks up the strategy for that algorithm name.
3. The strategy runs a single Lua script in Redis and turns the result into an `allowed / remaining / limit` response.
4. The controller returns `200` if allowed or `429` if blocked.

### Algorithms

| | Fixed window | Sliding window |
|---|---|---|
| Redis structure | One counter with a TTL | Sorted set of request timestamps |
| Idea | Count requests in each fixed time slice | Count requests in the last N seconds, measured from now |
| Strength | Very cheap, one counter per client | No boundary-burst loophole |
| Weakness | Allows bursts across a window boundary | Stores one entry per allowed request |

## Design decisions

### Why Lua scripts

A plain `INCR` in Redis is atomic, but a rate limiter needs more than one step. In the fixed window, the counter is incremented and then given an expiry with a separate `EXPIRE` command. If the app crashes between those two calls, the key never expires and that client is blocked forever. The sliding window is harder still: it removes old entries, counts what is left, and adds the new request, and if those steps are separate commands then concurrent requests can all see "under the limit" at the same moment and all get through.

Redis runs commands on a single thread, and a Lua script executes as one uninterrupted unit. Putting each algorithm's full logic in a script makes it atomic, with a single round trip to Redis per request.

### Fixed window vs sliding window

Fixed window resets its counter when the window ends, so it forgets what happened just before the reset. A client can send the full limit at the end of one window and again at the start of the next.

`BoundaryBurstTest` demonstrates this with a limit of 5 per 3 seconds. It sends 1 request at t = 0 s, 4 requests at t = 2.5 s, then 5 requests at t = 3.2 s:

| Algorithm | Requests allowed in the ~0.7 s around the boundary |
|---|---|
| Fixed window | **9** (the counter reset, so all 5 late requests passed) |
| Sliding window | **5** (it remembered the earlier burst, so only 1 late request passed) |

Sliding window closes the loophole, and the load tests below show it costs no measurable throughput.

### Failing open when Redis is down

Redis is the limiter's only dependency. If it becomes unreachable, the service has to choose between **fail open** (allow requests) and **fail closed** (block them). Failing closed would turn a Redis outage into a total outage for every client, so the default is to **fail open**: requests are allowed and an error is logged. The behavior is controlled by a property:

```properties
ratelimiter.fail-open=true
```

### Strategy pattern

Each algorithm is a class implementing `RateLimiterStrategy` and registered as a Spring bean named after the algorithm (`FIXED_WINDOW`, `SLIDING_WINDOW`). The service picks one by name from a map of strategies, so adding an algorithm means adding one class and one Lua script, with no changes to the service or controller.

## API

### `POST /api/v1/check`

Request:

```json
{ "clientId": "user-42", "algorithm": "SLIDING_WINDOW" }
```

`algorithm` is optional and defaults to `SLIDING_WINDOW`. Supported values: `FIXED_WINDOW`, `SLIDING_WINDOW`.

Allowed (`200 OK`):

```json
{ "allowed": true, "remaining": 4, "limit": 5 }
```

Blocked (`429 Too Many Requests`):

```json
{ "allowed": false, "remaining": 0, "limit": 5 }
```

The default policy is **5 requests per 60 seconds per client**.

### `GET /api/v1/ping`

Writes and reads a key in Redis, to confirm the service and Redis are connected.

```json
{ "app": "ok", "redis": "pong" }
```

An interactive version of both endpoints is available in the Swagger UI.

## Testing

The tests run against a real Redis, so start one on `localhost:6379` first, then run:

```bash
./mvnw test        # Windows: mvnw.cmd test
```

| Test | What it proves |
|---|---|
| Concurrency test (fixed window) | 100 simultaneous requests from 50 threads: exactly 5 allowed, 95 blocked, and the Redis key always has a TTL |
| Sliding window test | Limit 3 per 2 seconds: first 3 allowed, 4th blocked, allowed again after the window passes |
| `BoundaryBurstTest` | Fixed window leaks across a window boundary and sliding window does not (see above) |

## Load test results

Load tests use [k6](https://k6.io) and live in `load-test.js`. The script has two scenarios:

1. **Throughput:** 50 virtual users for 20 seconds, each request using a random client from 10,000 IDs.
2. **Hot client:** 50 virtual users for 10 seconds, all hitting **one** client, to check that the limit holds under pressure.

`429` responses are treated as expected, not as failures, since they are correct answers for a rate limiter.

| Algorithm | Throughput | p95 latency | Hot client: allowed / total requests |
|---|---|---|---|
| Fixed window | ~2,160 to 2,200 req/s | ~27 ms | 5 / ~22,100 |
| Sliding window | ~2,130 to 2,260 req/s | ~26 to 27 ms | 5 / ~22,300 |

Notes on these numbers:

- They were measured on a laptop with the app, Redis and k6 all sharing the same machine, after the JVM had warmed up. They show relative behavior, not production capacity.
- The two algorithms were within run-to-run noise (about 1 to 3%) when runs were alternated, so sliding window's protection comes at no measurable throughput cost.
- Two of six repeated runs were excluded from the ranges above: one was a cold start, and one contained a single request that stalled for about 12.8 seconds, probably from contention on the machine (the cause was not investigated).

Reproduce:

```bash
k6 run --env ALGO=SLIDING_WINDOW load-test.js
k6 run --env ALGO=FIXED_WINDOW load-test.js
# optional: --env BASE_URL=http://localhost:8080
```

## Running locally

### Option 1: Docker Compose (app and Redis)

```bash
docker compose up --build
```

The service is then available at `http://localhost:8080` (Swagger UI at `/swagger-ui/index.html`). The compose file publishes Redis on host port 6379, so stop any local Redis first or change the mapping.

### Option 2: Local Redis and Maven

```bash
# with Redis running on localhost:6379
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

### Configuration

| Setting | Default | Purpose |
|---|---|---|
| `SPRING_DATA_REDIS_URL` | not set | Full Redis URL (takes precedence over host and port) |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | `localhost` / `6379` | Redis location |
| `ratelimiter.fail-open` | `true` | Allow requests when Redis is unreachable |

## Deployment

The live demo is deployed on Render using `Dockerfile.render`, which runs Redis and the Spring Boot app in **one container** to stay on a free tier. That means the demo is a single instance and its data resets on every restart.

The application itself is stateless and reads its Redis location from configuration, so multiple instances can share one Redis. The `Dockerfile` and `docker-compose.yml` show that separate-container setup.

## Project structure

```
src/main/java/com/niyamath/ratelimiter
  config/       ScriptConfig            registers the Lua scripts as beans
  controller/   CheckController         REST endpoints
  models/       CheckRequest, CheckResponse
  service/      RateLimiterService      picks a strategy, handles Redis failures
  strategy/     RateLimiterStrategy, FixedWindowStrategy, SlidingWindowStrategy
src/main/resources/scripts/
  fixed_window.lua, sliding_window.lua
load-test.js                            k6 load test script
Dockerfile, Dockerfile.render, docker-compose.yml
```

## Limitations and future work

Scope decisions made to ship a focused project:

- **Token bucket** is not implemented. The strategy interface makes it a small addition.
- **Limits are fixed defaults** (5 per 60 seconds). Per-client or per-route configuration is not supported yet.
- **No `Retry-After` or `X-RateLimit-*` response headers.** Blocked responses carry the result in the body only.
- **An unknown algorithm name returns a 500** instead of a clean 400 error.
- **Tests need a locally running Redis.** Testcontainers would make them self-contained.
- **Single Redis node.** Running against Redis Cluster would need key hash tags so each script's keys land on one shard.
- **No metrics endpoint** for allowed and blocked counts.
