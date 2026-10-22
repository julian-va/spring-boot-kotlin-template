---
name: kotlin-backend
description: Write, refactor and review Kotlin/Spring Boot backend code following hexagonal architecture, constructor injection, coroutines and virtual threads, kotlin-test + JUnit 5 and explicit error handling.
version: 0.2.0
tags: [kotlin, backend, spring, guidelines]
---

# Skill: Kotlin Backend Development

## Purpose

This skill defines how the AI agent should write, refactor and review Kotlin code in this repository. It is both human-readable guidance and machine-consumable metadata so automation (CI, bots) can validate requests against the rules.

---

## Language & Version

- Kotlin 2.3.x on JDK 25
- Spring Boot 4.x, Spring Framework 7, Jackson 3
- Prefer modern Kotlin: data classes, sealed interfaces/classes, value classes, extension functions, coroutines
- No Lombok. It is redundant in Kotlin and conflicts with the compiler-generated members.

## Build tools

- Gradle 9.x with the Kotlin DSL (`build.gradle.kts`) — the only build in this repo
- Use the wrapper (`./gradlew`); never a bare `gradle`

---

## Code Style Rules

- Prefer `val` over `var`; immutability is the default
- Use expression bodies for simple functions
- One public class per file, except sealed hierarchies and small DTOs that belong together
- Max function length: 30 lines (exceptions allowed with justification)
- Meaningful names; no abbreviations that need a comment
- Follow the official Kotlin coding conventions (4-space indent, no wildcard imports, trailing commas allowed)

---

## Architecture Rules

- Follow Hexagonal (Ports & Adapters) / Clean Architecture principles. Emphasize clear boundaries between core business logic and external frameworks.
  - **Domain (Core):** entities, value objects, domain services and domain exceptions — pure Kotlin, no framework types.
  - **Domain Use Cases:** use-case interfaces and implementations. Orchestrate domain objects and call gateways.
  - **Domain Gateways:** interfaces (outbound ports) for external resources like repositories and email services.
  - **Infrastructure (Adapters):** inbound adapters (`entrypoint`), outbound adapters (`adapter`) and technical clients (`provider`). Keep them under `infrastructure`.
  - **Framework & Drivers:** Spring, JPA, web servers, DB drivers — outermost layer only.

- Dependency rule: code dependencies point inward. Inner layers (domain, use cases) must not depend on adapters or framework types.

- Use cases and gateways:
  - **Use cases (domain layer):** interfaces and implementations that orchestrate business logic (e.g., `CreateUserUseCase`, `CreateUserUseCaseImpl`).
  - **Gateways (domain layer):** interfaces for external resources (e.g., `UserGateway`, `EmailGateway`).
  - Outbound adapters live in `infrastructure.adapter.*`, implement the gateway interfaces and translate between domain and technical types.
  - Technical clients live in `infrastructure.provider.*` and must not import `domain` types.
  - Entry points live in `infrastructure.entrypoint.*` and call use-case interfaces.

- Adapter vs provider:
  - `adapter` implements a gateway and may import `domain`; it owns the mapping between domain and technical types.
  - `provider` is a framework client (JPA, Elasticsearch, REST, SMTP, S3) and must not import `domain`.
  - Create a provider only when it is reused by two or more adapters or isolates a framework you may replace. Otherwise, let the adapter use the framework directly.

- Mapping and boundaries:
  - Keep DTOs, REST models and mappers inside adapter layers; convert to/from domain objects at the boundary.
  - Do not leak framework types (`@Entity`, `HttpServletRequest`, `Page`) into `domain` or `usecase` packages.
  - Use Kotlin extension functions for mapping (`fun UserEntity.toDomain() = ...`), not mappers that depend on Spring.

- Example package layout (lowercase and singular):
  - `com.example.domain.model` — entities and value objects
  - `com.example.domain.exception` — domain exceptions
  - `com.example.domain.usecase` — use-case interfaces and implementations
  - `com.example.domain.gateway` — outbound port interfaces
  - `com.example.infrastructure.entrypoint` — inbound adapters (controllers, DTOs, mappers)
  - `com.example.infrastructure.adapter` — gateway implementations (JPA, email, security)
  - `com.example.infrastructure.provider` — technical clients (Spring Data repositories, entities, SMTP, Elasticsearch, REST, S3)
  - `com.example.infrastructure.config` — bean configuration

- **Terminology note:** `entrypoint` and `provider` are project conventions, not canonical terms. Hexagonal calls them driving adapters and driven adapters; Clean Architecture calls the outer ring Frameworks & Drivers.

- Minimal layout:

```text
src/main/kotlin/com/example
  /domain
    /model
    /exception
    /usecase
    /gateway
  /infrastructure
    /entrypoint
    /adapter
    /provider
    /config
src/test/kotlin/com/example
```

- Minimal ASCII diagram:

  [Entrypoint (REST)] -> [Use Case] -> [Gateway] -> [Adapter] -> [Provider]

---

## Kotlin Language Rules

- **Nullability is part of the type.** Never use `!!`. Use `requireNotNull`, `?:`, `?.let` or early returns.
- **No `lateinit`** outside tests and framework-required cases.
- **Data classes** for DTOs and value objects. They generate `equals`, `hashCode`, `toString` and `copy` — do not reimplement them.
  - Do **not** use a data class for a JPA entity: Hibernate needs a no-arg constructor and mutable state. Use a normal class with `var` fields and `@Entity`.
  - A data class holding secrets (passwords, tokens) generates a `toString` that leaks them into logs. Override `toString` or keep secrets out of data classes.
- **Sealed interfaces** for closed result/error hierarchies; `when` over them is exhaustive without `else`.
- **Default arguments** instead of overloads. Remember that JaCoCo counts the generated `$default` bridge.
- **Extension functions** for mapping and small helpers, never for business logic that belongs to a use case.
- **`require` / `check` / `error`** for preconditions and invariants: `require` throws `IllegalArgumentException`, `check` and `error` throw `IllegalStateException`.
- **Annotations on properties:** for Bean Validation use `@field:NotBlank`, `@field:Email`, etc. The compiler flag `-Xannotation-default-target=param-property` changes the default target but does not remove the need for `@field:` when the constraint must hit the backing field.
- **Jackson:** `jackson-module-kotlin` plus `kotlin-reflect` are required for constructor binding. Kotlin default values are honored on deserialization; a missing non-null property without a default fails, which is the behavior you want.
- **No wildcard imports** (`import foo.*`), except in very small local files where the project already does it.

---

## Framework Rules (Spring Boot 4)

- Constructor injection only. Kotlin's single-primary-constructor syntax removes the need for `@Autowired`.
- No field injection.
- Prefer `@Service`, `@Component`, `@Repository` stereotypes on adapter classes.
- Use `@ConfigurationProperties` with constructor binding and `val` properties; validate them with `@field:` constraints.
- Logging: SLF4J (Kotlin logging wrappers are fine). Never `println`.
- Controllers return `ResponseEntity` or DTOs, never domain entities.
- `@Transactional` belongs to the use-case implementation, not the controller.

Example controller:

```kotlin
@RestController
@RequestMapping("/api/users")
class UserController(
	private val createUser: CreateUserUseCase,
	private val mapper: UserDtoMapper,
) {

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@Valid @RequestBody request: CreateUserRequest): Map<String, Long> =
		mapOf("id" to createUser.create(mapper.toCommand(request)))
}
```

Example use case and gateway:

```kotlin
// domain/usecase/CreateUserUseCase.kt
interface CreateUserUseCase {
	fun create(command: CreateUserCommand): Long
}

// domain/usecase/CreateUserUseCaseImpl.kt
class CreateUserUseCaseImpl(private val gateway: UserGateway) : CreateUserUseCase {
	override fun create(command: CreateUserCommand): Long {
		val user = User.of(command.name, command.email)
		return gateway.save(user).id
	}
}

// domain/gateway/UserGateway.kt
interface UserGateway {
	fun save(user: User): User
	fun findById(id: Long): User?
}
```

Kotlin gateways return `T?`, not `Optional<T>`.

---

## Concurrency and Threading

Two mechanisms are available; pick one per code path and do not mix them.

- **Virtual threads (this template):** `spring.threads.virtual.enabled: true` makes Tomcat serve each request on a virtual thread. Blocking code keeps working; no rewrite needed. Use `Executors.newVirtualThreadPerTaskExecutor()` for fire-and-forget work with a bounded lifetime.
- **Coroutines:** use `suspend` functions and structured concurrency (`coroutineScope`, `supervisorScope`).
  - Never use `GlobalScope`.
  - Never call `runBlocking` in a request path; it is for `main` and tests only.
  - Move blocking I/O (JDBC, blocking HTTP clients) to `Dispatchers.IO` with `withContext`.
  - Do not `launch` unstructured work that outlives the request.
- **Context propagation:** `ThreadLocal` state such as MDC and Spring Security's `SecurityContext` is not inherited by virtual threads or coroutines.
  - Coroutines: use `ThreadContextElement` (or Micrometer Context Propagation `ContextSnapshot`).
  - Other executors: wrap the task (`ContextSnapshot.captureAll().wrap { ... }`) or pass the `Authentication` explicitly.
- Avoid `new Thread(...)`; use virtual threads or coroutine dispatchers.
- Keep shared mutable state minimal; prefer immutable domain objects.

---

## Error Handling

- Do not throw generic `Exception` or catch `Throwable`.
- Domain: throw specific exceptions (`UserNotFoundException`) or return a sealed result when the failure is expected and part of the contract.
- Adapters: translate technical exceptions (`DataAccessException`, `WebClientResponseException`) into domain ones.
- HTTP: a single `@RestControllerAdvice` returning RFC 9457 `ProblemDetail`, without stack traces or internal class names.

```kotlin
sealed interface CreateUserResult {
	data class Created(val id: Long) : CreateUserResult
	data class Invalid(val reason: String) : CreateUserResult
}
```

Use a sealed result when the caller must handle every outcome; use exceptions for genuinely exceptional conditions.

---

## Testing Rules

- JUnit 5 + `kotlin-test` assertions (`assertEquals`, `assertContains`, `assertFailsWith`).
- MockK for mocking (`io.mockk:mockk`). Mockito is acceptable for legacy tests, but MockK is the Kotlin-native choice.
- Backtick test names: ``fun `creates a user and returns its id`()``.
- Favor unit tests; require integration tests for critical flows and CI gating.
- Use `@SpringBootTest` for context tests and MockMvc for web slices.
- Spring Security tests: `spring-boot-starter-security-test` with `@WithMockUser`.

```kotlin
class CreateUserUseCaseTest {

	private val repository = mockk<UserGateway>()
	private val useCase = CreateUserUseCaseImpl(repository)

	@Test
	fun `creates a user and returns its id`() {
		every { repository.save(any()) } returns User(1, "Alice", "a@example.com")

		val id = useCase.create(CreateUserCommand("Alice", "a@example.com"))

		assertEquals(1, id)
		verify(exactly = 1) { repository.save(any()) }
	}
}
```

---

## Forbidden Patterns

- No `!!` (non-null assertion)
- No `lateinit` outside tests and framework hooks
- No `GlobalScope`
- No `runBlocking` in request-handling code
- No Lombok
- No field injection (`@Autowired` on a property)
- No `System.out.println` / `println` in production code
- No God classes, magic numbers or business logic in static/companion utilities
- No mutable `var` in DTOs when `val` is enough

---

## Reasoning Checklist (agent must return before code)

When asked to implement or modify code, the agent must return a short checklist containing:

1. Affected layer(s) (entrypoint/usecase/domain.model/domain.gateway/adapter/provider)
2. Chosen abstraction(s) and DTOs/entities
3. Design validation vs. the rules above (list any rule exceptions)
4. Use cases and gateways affected, mapper responsibilities (DTO -> domain)
5. Dependency validation: confirm no inward dependencies are violated, and that `provider` does not import `domain`
6. Concurrency choice: virtual threads or coroutines, and how context is propagated

Example response the agent must produce before code:

```
Layer: domain/usecase + infrastructure/adapter/jpa
Abstraction: CreateUserUseCase (interface + implementation) + UserGatewayAdapter
Validation: constructor injection, domain layer untouched, provider has no domain types, function length <= 30
Concurrency: virtual threads (blocking JPA), no ThreadLocal handoff
```

If a request violates the rules, explain why and propose an alternative.

---

## Examples (project structure)

### Hexagonal Architecture - Detailed Structure

```text
src/main/kotlin/com/example
├── MainApplication.kt
├── domain
│   ├── model
│   │   ├── User.kt
│   │   └── Email.kt
│   ├── exception
│   │   ├── UserNotFoundException.kt
│   │   └── InvalidEmailException.kt
│   ├── usecase
│   │   ├── CreateUserUseCase.kt
│   │   ├── GetUserUseCase.kt
│   │   └── CreateUserUseCaseImpl.kt
│   └── gateway
│       ├── UserGateway.kt
│       ├── EmailGateway.kt
│       └── PasswordEncoderGateway.kt
└── infrastructure
    ├── entrypoint
    │   └── rest
    │       ├── UserController.kt
    │       ├── exception
    │       │   └── ApiExceptionHandler.kt
    │       ├── dto
    │       │   ├── CreateUserRequest.kt
    │       │   ├── UpdateUserRequest.kt
    │       │   └── UserResponse.kt
    │       └── mapper
    │           └── UserDtoMapper.kt
    ├── adapter
    │   ├── jpa
    │   │   ├── UserRepositoryAdapter.kt
    │   │   └── mapper
    │   │       └── UserMapper.kt
    │   ├── email
    │   │   └── EmailAdapter.kt
    │   └── security
    │       └── PasswordEncoderAdapter.kt
    ├── provider
    │   ├── jpa
    │   │   ├── UserJpaRepository.kt
    │   │   └── entity
    │   │       └── UserEntity.kt
    │   └── smtp
    │       └── SmtpClient.kt
    └── config
        └── BeanConfiguration.kt
```

In a multi-module build, each top-level folder (`domain`, `infrastructure`) can become a Gradle module with its own `src/main/kotlin` and `build.gradle.kts`.

Sample flow (entrypoint -> use case -> gateway -> adapter -> provider):

```kotlin
// domain/usecase/CreateUserCommand.kt
data class CreateUserCommand(val name: String, val email: String)

// infrastructure/entrypoint/rest/dto/CreateUserRequest.kt
data class CreateUserRequest(
	@field:NotBlank val name: String,
	@field:Email val email: String,
)

// infrastructure/entrypoint/rest/mapper/UserDtoMapper.kt
@Component
class UserDtoMapper {
	fun toCommand(request: CreateUserRequest) = CreateUserCommand(request.name, request.email)
}

// infrastructure/adapter/jpa/UserRepositoryAdapter.kt
@Component
class UserRepositoryAdapter(
	private val jpaRepository: UserJpaRepository,
	private val mapper: UserMapper,
) : UserGateway {

	override fun save(user: User): User =
		mapper.toDomain(jpaRepository.save(mapper.toEntity(user)))

	override fun findById(id: Long): User? =
		jpaRepository.findById(id).map(mapper::toDomain).orElse(null)
}

// infrastructure/adapter/jpa/mapper/UserMapper.kt
@Component
class UserMapper {
	fun toEntity(user: User) = UserEntity(name = user.name, email = user.email.value)
	fun toDomain(entity: UserEntity) = User.of(entity.name, entity.email)
}
```

---

## CI / Automation (suggested)

Add a GitHub Actions job to run:

- JDK 25
- `./gradlew build --no-daemon` (compiles, tests and enforces the coverage gate)
- Optional: `./gradlew detekt` and `./gradlew ktlintCheck` once those tools are configured

```yaml
name: Kotlin CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
      - run: ./gradlew build --no-daemon
```

---

## Architecture verification (optional)

- **ArchUnit** works with Kotlin classes. Remember that Kotlin classes depend on the stdlib (`kotlin..`, `kotlin.jvm.internal..`), so the allowed packages must include it.

```kotlin
@AnalyzeClasses(packages = "com.example")
class ArchitectureTest {

	@ArchTest
	val domainShouldNotDependOnInfrastructure: ArchRule =
		noClasses().that().resideInAPackage("..domain..")
			.should().dependOnClassesThat().resideInAPackage("..infrastructure..")

	@ArchTest
	val useCasesShouldOnlyDependOnDomain: ArchRule =
		classes().that().resideInAPackage("..domain.usecase..")
			.should().onlyDependOnClassesThat()
			.resideInAnyPackage("..domain..", "java..", "kotlin..", "org.slf4j..")

	@ArchTest
	val providersShouldNotDependOnDomain: ArchRule =
		noClasses().that().resideInAPackage("..infrastructure.provider..")
			.should().dependOnClassesThat().resideInAPackage("..domain..")
}
```

- **Konsist** is a Kotlin-native alternative that understands declarations, annotations and file structure; prefer it for new architecture rules.

---

## Transactions & AOP

- Apply `@Transactional` and cross-cutting concerns at the use-case implementation, not in controllers.
- Repositories under `infrastructure.provider` already manage their own transactions; do not spread `@Transactional` across providers.
- Kotlin `@Transactional` on an `open` class or an interface implementation: remember that Spring AOP needs a proxy. Classes are `final` by default; the `kotlin("plugin.spring")` plugin opens `@Component`-annotated classes, but classes annotated only with `@Transactional` may need `@Component` or `allOpen`.
- Use aspects sparingly, only for genuine cross-cutting concerns.

---

## Notes & Exceptions

- The function-length rule can be relaxed when a refactor is documented in the PR description.
- MockK/Mockito are allowed for legacy code where dependency inversion is impractical.
- `data class` for JPA entities is forbidden; use a normal class.

## Change log

- 0.2.0: Lowercase singular packages (`entrypoint`, `adapter`, `provider`), adapter/provider boundary rule and ArchUnit provider rule
- 0.1.0: Initial Kotlin adaptation of the java-backend skill
