# Demo video script (about 12-13 min, screen recording + voice)

**Before recording**

1. `mvn spring-boot:run -Dspring-boot.run.arguments=--app.seed-demo=true` (creates both sample organizations and `demo/embed_demo.html`).
2. Open http://localhost:8080 for the dashboard and `demo/embed_demo.html` (double-click it) for the external page.
3. Use one browser profile per organization, or log out and in between them.
4. To show LLM mode, put your `ANTHROPIC_API_KEY` in `.env`, restart, and ask one question first. `llm_used` is shown in the "How was this decided?" panel data.

| Time | What to show | What to say |
|---|---|---|
| 0:00-1:00 | README top | The problem: organizations want an assistant over their PDFs. Key idea: the LLM handles language, System 1 models make decisions, application logic orchestrates. |
| 1:00-3:00 | **Product walkthrough.** Register a new organization, open the dashboard, upload a valid PDF, then try a 21-page PDF and a .txt file (show the errors). Delete and replace a document. Show the usage counters. | Limits (10 docs / 20 pages / 10 MB) and meaningful error messages. |
| 3:00-4:30 | Assistant section: copy the URL, regenerate the token (the old URL now says invalid), copy the iframe and JS embed code. | The token is random and carries no credentials. An admin token can't be used as an assistant token, and the other way round. |
| 4:30-5:30 | `demo/embed_demo.html` with both assistants embedded. | Embedding works from any page, and visitors never log in. |
| 5:30-7:30 | **Architecture.** Show the README diagram: frontend → `PublicController` → token validation → org-scoped chunks → `Pipeline` → System 1 models / `LlmService` → answer + references. Open `Pipeline.java` and `Decisions.java` briefly. | Walk the LLM / System 1 / application logic table and say why `core/` has no Spring in it. Cover heading-aware chunking with page numbers, BM25, the relevance threshold, and that references come from the app, not the LLM. |
| 7:30-10:00 | **AI decision flow.** Ask G5 ("2nd year, 3.6 GPA, sports scholarship, merit scholarship?") and open "How was this decided?". Then G6 (eligible), G8 (missing info, so it asks for what's missing) and Z4 (warranty: months + liquid-damage exclusion). | Inputs → LLM decomposes into facts + criteria → three Boolean models run in parallel (`ExecutorService`) → combine (ALL must hold) → score → LLM writes the answer. Explain data flow only, not private reasoning. |
| 10:00-11:00 | Other question types: direct (G1), procedural (G4), comparison (G9), **not in knowledge base** (G10). | Cites `Scholarship_Rules.pdf — Page 4`. For an unknown question it refuses to guess and counts it as "unable to answer". |
| 11:00-12:30 | **Isolation.** Ask the Zenith assistant "What GPA do I need for the merit scholarship?" (not in KB). Ask the Greenfield assistant about returns (not in KB). Run `mvn test` and show it passing. | The tenant filter is `WHERE org_id = ?` before any ranking, and `ApiSecurityTest` proves it through the real API. |
| 12:30-13:00 | README section 9 (limitations). | Be upfront: the offline extractor is pattern-based, retrieval is lexical, auth is simple. |
