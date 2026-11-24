import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import { AdminError, failureOf, type Account, type AdminApi, type Submission } from "./api";
import { messages, languages } from "./i18n";

afterEach(cleanup);

const pending: Submission = {
  submission_id: "s1", profile_id: "p1", version: 1, status: "pending", submitted_at: "2026-11-23T10:00:00Z",
  published: false, latest: true, decision: null,
  content: { name: "Perfil de prueba", description: "Ficticio", trades: ["plumbing"], services: ["Fugas"],
    coverage: [{ code: "33044", name: "Oviedo" }], languages: ["es"], phone: "+34 000 000 290" },
};

function fakeApi(account: Account | null, overrides: Partial<AdminApi> = {}): AdminApi {
  return {
    account: vi.fn(async () => account),
    signIn: vi.fn(async () => account!),
    signOut: vi.fn(async () => {}),
    submissions: vi.fn(async () => [pending]),
    review: vi.fn(async () => ({ status: "rejected" as const })),
    incidents: vi.fn(async () => []),
    settleIncident: vi.fn(async () => ({ status: "open" as const })),
    decidePerformance: vi.fn(async () => ({ status: "resolved" as const })),
    evidenceUrl: vi.fn(async () => "https://example.org/signed"),
    ...overrides,
  };
}

describe("profile review panel", () => {
  it("does not load any submission for an account without administration", async () => {
    const api = fakeApi({ email: "user@example.org", is_administrator: false });
    render(<App api={api} language="es" />);
    expect(await screen.findByText(messages.es.notAdministrator)).toBeTruthy();
    expect(api.submissions).not.toHaveBeenCalled();
  });

  it("signs in and lists pending submissions with their submitted content", async () => {
    const admin = { email: "admin@example.org", is_administrator: true };
    const api = fakeApi(null, { signIn: vi.fn(async () => admin) });
    render(<App api={api} language="es" />);
    await userEvent.type(await screen.findByLabelText(messages.es.email), "admin@example.org");
    await userEvent.type(screen.getByLabelText(messages.es.password), "secret-password");
    await userEvent.click(screen.getByRole("button", { name: messages.es.signIn }));
    expect(api.signIn).toHaveBeenCalledWith("admin@example.org", "secret-password");
    expect(await screen.findByText("Perfil de prueba")).toBeTruthy();
    expect(screen.getByText(/Oviedo/)).toBeTruthy();
    expect(api.submissions).toHaveBeenCalledWith("pending");
  });

  it("does not present services, prices or covers for review: owners publish them directly", async () => {
    const withOffer: Submission = { ...pending, content: { ...pending.content,
      prices: [{ service: "Reparación de fuga", scope: "Una hora", unit: "hour", amount_cents: 3500 }] } };
    const api = fakeApi({ email: "admin@example.org", is_administrator: true },
      { submissions: vi.fn(async () => [withOffer]) });
    render(<App api={api} language="es" />);
    await screen.findByText("Perfil de prueba");
    expect(screen.queryByText(messages.es.services)).toBeNull();
    expect(screen.queryByText(messages.es.prices)).toBeNull();
    expect(screen.queryByText(/Fugas/)).toBeNull();
    expect(screen.queryByText(/35\.00/)).toBeNull();
    expect(screen.queryByText(/portada/i)).toBeNull();
  });

  it("requires a reason and sends the rejection for that exact submission", async () => {
    const api = fakeApi({ email: "admin@example.org", is_administrator: true });
    render(<App api={api} language="es" />);
    const reject = await screen.findByRole("button", { name: messages.es.reject });
    expect((reject as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(messages.es.reason), "Falta la cobertura");
    await userEvent.click(reject);
    expect(api.review).toHaveBeenCalledWith("s1", false, "Falta la cobertura");
    expect(await screen.findByText(messages.es.rejectedDone)).toBeTruthy();
    expect(api.submissions).toHaveBeenCalledTimes(2);
  });

  it("shows a server refusal as an error, never as a confirmation", async () => {
    const api = fakeApi({ email: "admin@example.org", is_administrator: true }, {
      review: vi.fn(async () => { throw new AdminError("profile_changed"); }),
    });
    render(<App api={api} language="es" />);
    await userEvent.type(await screen.findByLabelText(messages.es.reason), "Correcto");
    await userEvent.click(screen.getByRole("button", { name: messages.es.approve }));
    expect(await screen.findByText(messages.es.error_profile_changed)).toBeTruthy();
    expect(screen.queryByText(messages.es.approvedDone)).toBeNull();
  });

  it("switches the interface language without translating professional content", async () => {
    const api = fakeApi({ email: "admin@example.org", is_administrator: true });
    render(<App api={api} language="es" />);
    await screen.findByText("Perfil de prueba");
    await userEvent.selectOptions(screen.getByLabelText(messages.es.language), "de");
    expect(await screen.findByRole("button", { name: messages.de.approve })).toBeTruthy();
    expect(screen.getByText("Perfil de prueba")).toBeTruthy();
  });

  it("marks what a change to a published profile alters, next to the public value", async () => {
    const change: Submission = { ...pending, version: 2, published: true,
      content: { ...pending.content, description: "Nueva descripción" },
      public_content: { ...pending.content, description: "Ficticio" } };
    const api = fakeApi({ email: "admin@example.org", is_administrator: true }, {
      submissions: vi.fn(async () => [change]),
    });
    render(<App api={api} language="es" />);
    expect(await screen.findByText("Nueva descripción")).toBeTruthy();
    const changed = screen.getAllByText(messages.es.changed);
    expect(changed).toHaveLength(1);
    expect(screen.getByText(messages.es.publicValue.replace("{value}", "Ficticio"))).toBeTruthy();
  });

  it("shows the audit of decided submissions in the history", async () => {
    const decided: Submission = { ...pending, status: "rejected",
      decision: { approved: false, reason: "Falta la cobertura", decided_at: "2026-11-23T11:00:00Z", administrator_email: "admin@example.org" } };
    const api = fakeApi({ email: "admin@example.org", is_administrator: true }, {
      submissions: vi.fn(async (status) => (status === "all" ? [decided] : [])),
    });
    render(<App api={api} language="es" />);
    await userEvent.click(await screen.findByRole("button", { name: messages.es.history }));
    expect(await screen.findByText(/Falta la cobertura/)).toBeTruthy();
    expect(screen.getAllByText(/admin@example.org/).length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: messages.es.approve })).toBeNull();
  });
});

describe("translations and errors", () => {
  it("every language defines every message", () => {
    const keys = Object.keys(messages.es).sort();
    for (const language of languages) expect(Object.keys(messages[language]).sort()).toEqual(keys);
    for (const language of languages) for (const value of Object.values(messages[language])) expect(value.trim()).not.toBe("");
  });

  it("maps server errors to stable categories", () => {
    expect(failureOf({ code: "42501", message: "permission denied for function" })).toBe("permission_denied");
    expect(failureOf({ code: "22023", message: "reason_required" })).toBe("reason_required");
    expect(failureOf({ code: "40001", message: "profile_changed" })).toBe("profile_changed");
    expect(failureOf({ message: "TypeError: Failed to fetch" })).toBe("network");
  });
});
