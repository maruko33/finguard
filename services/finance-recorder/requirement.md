FinGuard Phase 9 — Personal Finance Software Requirements Specification (Reviewed Draft)
Version: 0.2-draft
Date: 2026-10-14
Status: Proposed revisions — not yet approved or implemented
Author / product owner: FinGuard project owner
Review support: AI-assisted requirements and architecture review
Scope: Angular Web Client + existing Spring Boot backend (separate finance domain) + PostgreSQL.
Evidence/decision legend
- [SOURCE]: existing requirement or intent in Software Requirement.pdf (12 pages, supplied Oct 2026).
- [PROPOSED]: suggested clarification, refinement, or implementation boundary; confirm with the product owner.
- [TBD]: cannot be decided solely from the supplied requirement; an explicit product decision is needed.

(G) Goals
(G.1) Context and Overall Objectives
[SOURCE] Build an AI-assisted personal bookkeeping application that improves transaction entry/import, categorization, and financial visualization.
[PROPOSED] Deliver a personal-finance web application that allows users to maintain accurate income and expense records, review CSV imports before committing them, understand historical spending, and manage budgets. AI-assisted categorization and conversational analysis form a later increment of Phase 9, not a dependency for creating an accurate ledger.
(G.2) Current Situation
[SOURCE] Traditional bookkeeping requires repeated manual imports, difficult categorization, and insufficiently clear graphical summaries.
(G.3) Expected Benefits
[SOURCE] Easier imports, classification assistance, and clear graphical views.
[PROPOSED] Measurable outcomes:
1. A user can add, edit and review a transaction from the web interface.
2. A user can import a supported CSV, inspect validation/duplicate warnings, and confirm the import.
3. Monthly income, expense and net balance figures reconcile with the committed ledger.
4. A category can be corrected manually; auto-classification never silently overrides a confirmed manual choice.
(G.4) Functionality Overview
[SOURCE] Sign up/login; transaction insert/delete/modify; classification/tag editing; spending visualization; spending/saving goals; monthly/quarterly/yearly summaries; CSV/file imports; intended AI support.
[PROPOSED] MVP scope: authentication and ownership, manual ledger CRUD, categories, basic account selection, CSV preview/confirm, deterministic rules-first classification, monthly report and Angular dashboard.
[PROPOSED] Next increments: budget goals, quarterly/yearly reporting, AI suggestions/chat, more bank CSV templates, optional bank API connections, offline draft/sync support. These are not cancelled; they are staged to control risk.
(G.5) High-Level Usage Scenarios
UC-01 Sign up / Sign in — Authenticate, establish an authorized session, and open only the signed-in user's finance data.
UC-02 Record / Import — Choose manual or CSV; validate; classify; review; confirm; persist; return result. Manual entry can use a single-record review/confirmation rather than a full import session.
UC-03 View Entries — Filter by date/account/category/type; paginate; view details.
UC-04 Edit / Delete Entry — Change valid fields, preserve ownership, and update reports accordingly.
UC-05 View Reports — Select a reporting period; view totals and category distribution.
[PROPOSED] UC-06 Set Budget — Create a spending target and view actual-versus-target.
[PROPOSED] UC-07 Accept/Reject Classification — Confirm or adjust rule/AI-suggested categories.
(G.6) Limitations and Exclusions
[PROPOSED]
- Personal-finance tracking, not a regulated banking ledger, payment rail, trading engine, or tax-filing application.
- Currency: CAD for the initial MVP; no FX conversion yet. Store currency explicitly for forward compatibility.
- Browser-based web app; responsive mobile layout is supported. Native iOS/Android apps are outside the initial scope.
- Bank API synchronization is excluded from MVP pending provider/security review. CSV import is the initial acquisition method.
- No automated movement of funds or financial advice. AI classifications are suggestions and require user oversight.
- Offline sync with conflict resolution is deferred; an offline draft feature remains a separately estimated requirement.
(G.7) Stakeholders / Sources
Role	Direct or indirect	Interest
Product owner / primary user	Direct	Efficient personal bookkeeping and trustworthy reporting
Future individual users	Direct, proposed	Privacy, usability, data ownership
Developer / maintainer	Direct	Testability, maintainability, deployability
CSV data source / potential bank API provider	Indirect	Format/contract compatibility; no integration assumed
AI model provider (future increment)	Indirect	Controlled and privacy-reviewed inference integration


[SOURCE] Original scope identifies a single primary stakeholder.
[TBD] Is this a true multi-user product from MVP, or single-user-first with an identity model reserved for later? Registration/login in the original document suggests the former.
(E) Environment
(E.1) Glossary
Term	Meaning / proposed design convention
User	An authenticated application principal; controls only their own data
FinancialAccount	Logical source of funds (cash, chequing, savings, credit card); not the sign-in account
LedgerEntry	Personal income or expense record; distinct from FinGuard's Phase 8 risk-detection Transaction
Money	Decimal amount and ISO currency code; use Java BigDecimal / SQL NUMERIC, never floating point for money
Category	User-specific or predefined income/expense label
ImportBatch	One CSV import attempt with draft, review, confirmed or failed status
ImportCandidate	Parsed prospective entry not yet committed into the ledger
Duplicate candidate	Possible repeated import; requires review, not automatic destructive deletion
Idempotency	Repeating the same logical request must not accidentally create duplicate persisted entries
Budget	Spending target scoped to user, category (optional) and time period
Report	Derived totals based on committed ledger entries; not a rendered UI chart
PWA	Progressive Web App; optional browser capability, not synonymous with a native mobile app


(E.1.2) Domain Model
See phase9_domain_model.puml for the proposed domain class diagram.
[PROPOSED] Keep entities/value objects separate from controllers, infrastructure and Service implementations. The original diagram mixed those concerns. Existing Phase 8 Transaction and RiskWorker remain a separate domain.
(E.2) External Components
[SOURCE] Angular frontend, Spring Boot backend, PostgreSQL database.
[PROPOSED] Angular → REST/JSON API → finance module inside the existing Spring Boot deployable → PostgreSQL finance tables. Existing transaction/risk pipeline remains separately modeled. AI provider is an optional future outbound dependency; the application can function without it.
(E.3) Constraints
- [PROPOSED] Responsive web UI for desktop and mobile browsers; accessible form labels/keyboard navigation.
- [PROPOSED] API supports pagination and server-side authorization/validation.
- [PROPOSED] Cloud-public access is not permitted without HTTPS and functional authentication/authorization.
- [SOURCE] User wants entry creation during outages to be saved locally and resumed when connectivity returns.
- [PROPOSED / PHASE 9 LATER] Offline drafts + explicit sync, pending decisions about IndexedDB, local encryption, conflict policy and duplicated retries; this is more than a UI toggle.
(E.4) Assumptions
- [PROPOSED] MVP uses CAD, and the initial target locale is English; internationalization can be supported through Angular conventions later.
- [PROPOSED] Financial dates are grouped using an explicit user-selected date/time-zone convention (avoid silent UTC/month boundary errors).
- [TBD] Account management, identity provider vs. local authentication, data export/retention, and deployment exposure.
(E.5) Effects
[PROPOSED] The application stores sensitive personal spending information and can influence user budgeting choices. Incorrect categorization or reports can mislead users; default to reviewable data and auditable calculations. Do not represent budget predictions or AI commentary as guaranteed outcomes.
(E.6) Invariants (business rules)
1. Every committed ledger entry belongs to exactly one user, and every operation verifies that ownership on the server.
2. A committed entry has a valid type (INCOME/EXPENSE), strictly positive amount, supported currency, and valid entry date.
3. Money is never represented using Java float/double for persisted calculations.
4. A category associated with an entry must be available to that user and compatible with the entry type.
5. CSV preview does not by itself create committed entries; only a successful confirmation persists accepted candidates.
6. A suspected duplicate is flagged and explained, not silently discarded without review.
7. Reports derive from committed entries and apply consistent date and ownership filters.
8. An AI suggestion may be overridden by the user; suggestions do not mutate confirmed history without an authorized action.
(S) System
(S.1) Internal Components
[PROPOSED]
- Identity/Auth: account/session establishment, password or provider validation; not plain-text credentials in the domain model.
- Ledger: CRUD for LedgerEntry; ownership and validation.
- Classification: rule-based categorization first; optional AI classifier later.
- Import: CSV parser, data mapping, duplicate detection, staging and user confirmation.
- Reporting: aggregation and calculated summaries, consumed by Angular chart components.
- Budget: target definitions and actual-vs-budget calculations (post-MVP).
- Angular: input forms, lists, import preview, charts, authentication UI.
(S.2) Functional Requirements
ID	Requirement	Release / priority	Acceptance reference
FR-01	Register/sign in and sign out; limit access to authorized user	MVP / P0	AC-01
FR-02	Create one income/expense entry manually	MVP / P0	AC-02
FR-03	View, filter, paginate and retrieve own entries	MVP / P0	AC-03
FR-04	Edit and delete own entries	MVP / P0	AC-04
FR-05	Manage/assign entry categories	MVP / P0	AC-05
FR-06	Upload CSV and preview parsed rows with validation and suspected duplicates	MVP / P0	AC-06
FR-07	Confirm/cancel an import; persist accepted rows once	MVP / P0	AC-07
FR-08	Generate monthly income/expense/net totals and category breakdown	MVP / P0	AC-08
FR-09	Render dashboard graphs from aggregated REST responses	MVP / P0	AC-08
FR-10	Automatically suggest categories using deterministic rules	MVP / P0	AC-09
FR-11	Set spending/saving budgets and compare actuals	Later Phase 9 / P1	AC-10
FR-12	Quarterly/yearly report views	Later Phase 9 / P1	AC-11
FR-13	AI classification and optional conversational finance analysis	Later Phase 9 / P1	AC-12
FR-14	Offline draft and safe synchronization	Later Phase 9 / P1	AC-13
FR-15	Direct bank API import/synchronization	Future / P2	Provider feasibility TBD


(S.2.1) Nonfunctional Requirements (proposed targets, not measured facts)
ID	Requirement / acceptance direction
NFR-01 Security	HTTPS for deployed access; authenticated APIs; authorization at service/data-query level; safe secret injection; no raw credentials in Git/logs
NFR-02 Privacy	User data isolation; limit bank statement data sent to third-party AI; obtain explicit consent for future AI features
NFR-03 Data integrity	Use DB constraints, transactional write boundaries, retries with idempotency strategy and tested totals
NFR-04 Accessibility	Keyboard-accessible forms and errors, responsive layout, readable chart text alternative
NFR-05 Observability	Structured error logging without financial secrets; transaction/import correlation IDs
NFR-06 Maintainability	Separate finance package boundaries; Flyway migrations; OpenAPI contract; Automated tests
NFR-07 Performance	[TBD] Set measured P95 target and test dataset after MVP implementation; don't invent SLA numbers now
NFR-08 Availability	[TBD] Define deployment target and recoverability based on hosting architecture


(S.3) Interfaces
- Browser → API: HTTP(S) REST/JSON; cookie/session or token strategy [TBD]; Angular uses typed HTTP client service.
- Spring Boot → DB: PostgreSQL JDBC/JPA within backend; browser must never connect directly to PostgreSQL.
- Import: User uploads CSV; server parses and validates, returns a preview ID/result.
- Future AI API: Server-controlled provider calls and whitelisted tool-calling; never expose model API credentials in Angular.
Proposed API contracts (draft, not final OpenAPI):
Method	Endpoint	Purpose
POST	/api/v1/finance/entries	Create one entry
GET	/api/v1/finance/entries	Search/paginate own entries
PATCH	/api/v1/finance/entries/{id}	Update own entry
DELETE	/api/v1/finance/entries/{id}	Delete own entry
GET/POST	/api/v1/finance/categories	List/create categories
POST	/api/v1/finance/imports	Upload and stage CSV
GET	/api/v1/finance/imports/{id}	Retrieve preview/warnings
POST	/api/v1/finance/imports/{id}/confirm	Confirm accepted candidates idempotently
GET	/api/v1/finance/reports/monthly	Aggregate monthly report
GET/POST	/api/v1/finance/budgets	Later increment: budget management


(S.3.2) Wireframes
[PROPOSED] Minimum wireframes required before Angular implementation: Sign-in; Dashboard; New Entry; Entries List/Edit; CSV Upload/Preview. These can be low-fidelity sketches or Figma, but do not mark them as finished yet.
(S.4) Detailed Usage Scenarios
UC-02 CSV import (primary high-risk flow)
1. Precondition: user signed in and allowed to import data; an account/source can be selected.
2. User uploads supported CSV and selects mapping if required.
3. Backend validates file type/size/schema, parses candidates and calculates proposed categories.
4. Backend identifies exact repeated import identifiers and possible duplicate entries, returns warnings.
5. Angular displays review preview; user edits categories and chooses rows to accept/reject.
6. User confirms the ImportBatch; backend re-checks ownership, validity, and confirmation status.
7. Within a transaction, backend persists accepted entries and marks batch confirmed. Repeating confirm is safe.
8. UI presents imported/skipped/rejected counts; failures are actionable without duplicating committed entries.
UC-03 View entries — Precondition: authenticated. User filters entries by date/category/type/account; API returns only their own, paginated data; empty list displays a non-error empty state. Forbidden foreign record reads are denied.
UC-04 Edit entry — User opens an owned entry, submits valid updates, API validates category ownership and amount constraints, saves changes; monthly dashboard reflects the new committed state. Nonexistent or unowned IDs return appropriate safe error responses.
UC-05 View reports — User selects month; API computes totals from committed entries for the logged-in user; Angular presents trend/category charts. Empty month displays zeros rather than broken charts.
UC-06 Budget [later] — User selects target and period; service validates nonnegative/valid target and calculates actuals; report shows variance. Monthly rollover semantics [TBD].
UC-07 AI classification [later] — AI proposes a category with explanation and optional confidence; user reviews/overrides; sensitive financial information is minimized before external requests. No auto-write without explicit approved workflow.
(S.5) Prioritization
[PROPOSED] Use MVP/P0 for core data accuracy, auth, import, review and monthly insights. Budget, AI and multi-period reports are P1; direct bank API is P2 due to third-party integration and privacy dependencies. Original document stated all features are essential, but engineering delivery still requires iteration and explicit sequencing.
(S.6) Verification / Acceptance Criteria
ID	Verifiable outcome
AC-01	Authenticated user sees their entries; user A cannot read/update user B's entries via API
AC-02	Creating a valid CAD expense returns 201 and read-back matches amount/date/type/category
AC-03	Filters/pagination return correct subset; unauthorized access is blocked
AC-04	Edit/delete produces expected persisted results; report totals reflect edits
AC-05	Category ownership/type validation rejects an invalid category
AC-06	Import returns row counts, validation warnings and suspected duplicates before any committed ledger inserts
AC-07	Two identical confirm requests result in one accepted set of committed ledger entries; rejects are not inserted
AC-08	For known test fixtures, API totals reconcile exactly with ledger rows; Angular chart and text totals match
AC-09	Rule classifier suggests known category; user override is preserved
AC-10	Budget actual, target and variance agree with test ledger (later)
AC-11	Quarterly and yearly summaries reconcile with included months (later)
AC-12	AI failure does not block manual recording; AI suggestions remain reviewable (later)
AC-13	Offline draft survives a browser reload and sync retry does not duplicate entries (later, subject to storage threat model)


Traceability examples: UC-02 → FR-06/FR-07 → AC-06/AC-07 → ImportBatch, ImportCandidate, ImportService; UC-05 → FR-08/FR-09 → AC-08 → ReportingService and Angular charts.
(P) Project
(P.1) Roles and Personnel
[SOURCE] One developer. [PROPOSED] The project owner acts as Product Owner, Business Analyst, Architect and primary implementer. AI/Codex acts as a review/coding tool, not an accountable stakeholder or replacement approver. Architecture and acceptance decisions remain with the owner.
(P.2) Imposed Technical Choices
[SOURCE] Angular, Spring Boot and PostgreSQL.
[PROPOSED] Java 21; Flyway; Angular standalone components/TypeScript; OpenAPI; Docker Compose locally. Modular Monolith for finance alongside existing transaction/risk domain; isolate packages and schemas/tables appropriately.
(P.3) Schedule and Milestones
Use entry/exit criteria, not invented dates:
Gate	Deliverable	Exit criterion
9.0	Signed-off SRS	Product owner confirms MVP, AI/offline constraints, and acceptance criteria
9.1	Domain/UML + HLD/ADR	Clarified identities, ledger boundaries, data invariants and storage model
9.2	API/DB Design	Flyway/schema and OpenAPI reviewed before implementation
9.3	Backend implementation	Unit, integration, security ownership, CSV idempotency tests pass
9.4	Angular implementation	Manual entry/import/reports work through browser
9.5	E2E + release	Known-ledger reconciliation, no secrets in repo, repeatable local startup
9.6	Budget + AI increment	Optional increment after the reliable ledger is complete


(P.4) Tasks and Deliverables
Owner-first learning responsibilities: Decide requirements; draw sequence diagrams for CSV import and monthly report; define ledger invariants and schema; write first Controller–Service–Repository flow and critical tests; review all generated code.
Codex-suitable work: Generate DTO skeletons, OpenAPI types, repeatable Angular table/form boilerplate after contracts are approved, documentation formatting, fixture generators and secondary tests. No silent redesign of money semantics, authentication or data ownership.
(P.5) Required Technologies
Git/GitHub, Java 21, Spring Boot, PostgreSQL, Flyway, Angular CLI/TypeScript, Node LTS compatible with selected Angular version, Docker Compose, Maven, JUnit/Mockito, OpenAPI, PlantUML/Mermaid; potentially Testcontainers for integration tests. AWS Secrets Manager remains for ECS/RDS cloud deployment; local configuration stays separate.
(P.6) Risk Analysis
Risk	Impact	Proposed mitigation
Cross-user data exposure	Critical	Server-side ownership checks and security integration tests
Currency/rounding errors	High	BigDecimal, explicit currency, reporting reconciliation tests
Duplicate CSV import	High	Import batch state and idempotent confirmation; review suspicious duplicates
Incorrect AI categorization	Medium/high	Rule baseline; suggested vs confirmed category separation; user override
Sensitive CSV data leaked to AI	High	Default no external CSV forwarding; consent, minimization and threat review
Scope creep (AI + bank APIs + offline + budgets)	High	MVP gates; separate increments; ADRs
Offline draft data loss/conflicts	Medium/high	Deferred feature, explicit storage/sync design and retry tests
Uncontrolled AWS cost	Medium	Continue short-lived Terraform deployments and budget monitoring
Existing Transaction model conflated with finance	High	Dedicated LedgerEntry domain and separate API routes/tables


(P.7) Requirements Elicitation and Review
[SOURCE] Initial SRS supplied by project owner with first-person feature ideas and AI-generated class design.
[PROPOSED] Versioned change process: initial requirements → review questions → product-owner decisions → approved baseline → change log. No interviews or external stakeholder validations are claimed to have occurred.
Explicit Product Decisions Still Open (Do not let Codex silently choose)
1. Identity model: real multi-user registration in MVP, or only single-user initially with protected sessions?
2. Account model: choose one default cash account, or user-configurable cash/bank/credit-card accounts from MVP?
3. CSV formats: which Canadian-bank statement(s) will be supported first? Provide a sanitized sample.
4. Date rules: transaction booking date vs posted date; expected display timezone.
5. Deletion semantics: hard delete vs reversible soft delete; security/audit trade-off.
6. AI boundary: AI classification and chat before Phase 9 release, or a separate 9B milestone? What transaction data can leave the system?
7. Offline scope: queued edits or only locally saved drafts; how to handle storage of sensitive financial records?
8. Authentication approach: session/cookie vs token/provider; do not choose on implementation convenience alone.
UML Review Notes for the Original Diagram (pp. 4–8)
- Original PDF embeds Lucidchart-exported JSON as text/image instead of a readable rendered diagram. Export diagram as SVG/PNG or PlantUML next revision.
- Original TransactionService composes Review; prefer a use/dependency relationship; ImportBatch owns import candidates rather than a service owning transient Reviews.
- User composition to sign-in Account is unnecessary until exact ownership/lifecycle is defined; distinguish FinancialAccount clearly.
- User.login() and User.viewTransactions() belong to authentication/application concerns, not core entity methods.
- Authenticator is drawn as an interface but lacks a corresponding concrete implementation; avoid abstracting without concrete uses.
- TransactionClassifier.validate(...) conflates categorization, validation and duplicate detection; split responsibilities.
- Report.render(): ChartModel couples the backend to Angular presentation. Return aggregate DTOs, render in Angular.
- Duplicate lines connect concrete repository and report generator to their interfaces; use one UML realization (dashed line, hollow triangle), not repeated generalization lines.
- Original Transaction collides with Phase 8 risk pipeline. Finance-specific LedgerEntry avoids accidental coupling.
- Budget, ImportBatch, ImportCandidate, FinancialAccount, category ownership and user isolation are required to implement the proposed product scope reliably.
Next Learning Task: Sequence Diagram (product owner authored)
Draw UC-02: Import a CSV and Confirm Review yourself, using these lifelines if helpful:
User, Angular ImportPage, ImportController, ImportService, CsvParser, DuplicateDetector, LedgerEntryRepository, PostgreSQL.
Must include alt fragments for invalid rows, duplicate candidates, and repeated confirmation, plus the precise point where DB persistence happens. Do not make the UI access PostgreSQL directly. After you draft it, compare it with ImportBatch lifecycle and AC-06/AC-07.