import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import { AdminError, type AdminApi, type IncidentReview } from "./api";
import { messages } from "./i18n";

afterEach(cleanup);
const es = messages.es;
const admin = { email: "admin@example.org", is_administrator: true };

const open: IncidentReview = {
  id: "i1", kind: "quality", description: "Sigue goteando", performance: "acknowledged", status: "open",
  created_at: "2026-11-23T10:00:00Z", resolved_at: null, opened_by_role: "client",
  opening: { appointment_stage: "done", payment_mode: "integrated", pending_cents: 8000, delivered_cents: 0, blocks_delivery: true },
  can_settle: true, settlement: null, settlement_refunded_cents: null, settlement_delivered_cents: null,
  settlement_reason: null, settled_at: null, settled_by_email: null, performance_decision: null,
  performance_reason: null, performance_decided_at: null, performance_decided_by_email: null,
  appointment: { id: "a1", stage: "done", problem: "Fuga bajo el fregadero", profile_name: "Fontanería Ejemplo",
    requested_start: "2026-11-24T08:00:00Z", requested_end: "2026-11-24T09:00:00Z", started_at: "2026-11-24T08:05:00Z",
    done_at: "2026-11-24T09:00:00Z", close_due_at: "2026-11-26T09:00:00Z", completed_at: null, completion: null },
  conditions: { version: 1, scope: "Cambiar sifón", start_at: "2026-11-24T08:00:00Z", end_at: "2026-11-24T09:00:00Z",
    duration_minutes: 60, amount_cents: 8000, visit_fee_cents: null, materials_included: true, travel_included: true },
  payment: { mode: "integrated", agreed_cents: 8000, paid_cents: 8000, pending_cents: 8000, refunded_cents: 0, delivered_cents: 0 },
  movements: [{ kind: "paid", amount_cents: 8000, created_at: "2026-11-23T09:00:00Z", by: "client" }],
  evidence: [
    { id: "e1", kind: "message", note: null, author_role: "client", message_author_role: "professional",
      message_body: "Vuelvo mañana", message_created_at: "2026-11-24T09:10:00Z", attachment_kind: null,
      attachment_path: null, attachment_type: null, created_at: "2026-11-24T10:00:00Z" },
    { id: "e2", kind: "message", note: null, author_role: "client", message_author_role: "client", message_body: "",
      message_created_at: "2026-11-24T09:20:00Z", attachment_kind: "photo", attachment_path: "c/photo.jpg",
      attachment_type: "image/jpeg", created_at: "2026-11-24T10:01:00Z" },
  ],
};

function fakeApi(items: IncidentReview[], overrides: Partial<AdminApi> = {}): AdminApi {
  return {
    account: vi.fn(async () => admin), signIn: vi.fn(async () => admin), signOut: vi.fn(async () => {}),
    submissions: vi.fn(async () => []), review: vi.fn(async () => ({ status: "approved" as const })),
    workZoneChanges: vi.fn(async () => []),
    incidents: vi.fn(async () => items),
    settleIncident: vi.fn(async () => ({ status: "open" as const })),
    decidePerformance: vi.fn(async () => ({ status: "resolved" as const })),
    evidenceUrl: vi.fn(async () => "https://example.org/signed"),
    ...overrides,
  };
}

async function openIncidents(api: AdminApi) {
  render(<App api={api} language="es" />);
  await userEvent.click(await screen.findByRole("button", { name: es.sectionIncidents }));
  return screen.findByText("Sigue goteando");
}

describe("incident review panel", () => {
  it("shows the appointment, accepted conditions, balance, movements and contributed evidence", async () => {
    const api = fakeApi([open]);
    await openIncidents(api);
    expect(api.incidents).toHaveBeenCalledWith("open");
    expect(screen.getByText("Fuga bajo el fregadero")).toBeTruthy();
    expect(screen.getByText("Cambiar sifón")).toBeTruthy();
    expect(screen.getByText(/Pagado 80,00\s€ · Pendiente 80,00\s€/)).toBeTruthy();
    expect(screen.getByText(/Vuelvo mañana/)).toBeTruthy();
    expect(screen.getByText(es.privacyScope)).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: es.evidenceOpen }));
    expect(api.evidenceUrl).toHaveBeenCalledWith("c/photo.jpg");
  });

  it("splits the pending balance only after a reason, a valid amount and a confirmation", async () => {
    const api = fakeApi([open]);
    await openIncidents(api);
    const money = screen.getByRole("group", { name: es.moneyTitle });
    await userEvent.click(within(money).getByLabelText(es.settle_split));
    const review = within(money).getByRole("button", { name: es.settleReview });
    expect((review as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(within(money).getByLabelText(es.splitAmount), "80");
    await userEvent.type(within(money).getByLabelText(es.decisionReason), "Trabajo a medias");
    expect(within(money).getByText(es.splitInvalid)).toBeTruthy();
    expect((review as HTMLButtonElement).disabled).toBe(true);
    await userEvent.clear(within(money).getByLabelText(es.splitAmount));
    await userEvent.type(within(money).getByLabelText(es.splitAmount), "25,50");
    await userEvent.click(review);
    expect(api.settleIncident).not.toHaveBeenCalled();
    expect(within(money).getByText(/Se devolverán 25,50\s€ al cliente y se entregarán 54,50\s€/)).toBeTruthy();
    await userEvent.click(within(money).getByRole("button", { name: es.settleConfirm }));
    expect(api.settleIncident).toHaveBeenCalledWith("i1", "split", 2550, 8000, "Trabajo a medias");
    expect(await screen.findByText(es.settledNotice)).toBeTruthy();
  });

  it("records performance separately from the money", async () => {
    const api = fakeApi([open]);
    await openIncidents(api);
    const performance = screen.getByRole("group", { name: es.performanceTitle });
    await userEvent.click(within(performance).getByLabelText(es.decision_not_performed));
    await userEvent.type(within(performance).getByLabelText(es.decisionReason), "Nadie acudió");
    await userEvent.click(within(performance).getByRole("button", { name: es.performanceReview }));
    await userEvent.click(within(performance).getByRole("button", { name: es.performanceConfirm }));
    expect(api.decidePerformance).toHaveBeenCalledWith("i1", "not_performed", "Nadie acudió");
    expect(api.settleIncident).not.toHaveBeenCalled();
  });

  it("after the delivery there are no money actions, only the conclusion", async () => {
    const afterClose: IncidentReview = { ...open, id: "i2", can_settle: false,
      opening: { ...open.opening, appointment_stage: "completed", pending_cents: 0, delivered_cents: 8000, blocks_delivery: false },
      payment: { ...open.payment!, pending_cents: 0, delivered_cents: 8000 } };
    const api = fakeApi([afterClose]);
    await openIncidents(api);
    expect(screen.getByText(es.moneyNone_delivered)).toBeTruthy();
    expect(screen.queryByLabelText(es.settle_refund)).toBeNull();
    expect(screen.getByRole("group", { name: es.performanceTitle })).toBeTruthy();
  });

  it("direct and free appointments have no balance under the panel's control", async () => {
    const direct: IncidentReview = { ...open, id: "i3", description: "Directo", can_settle: false,
      opening: { ...open.opening, payment_mode: "direct", pending_cents: 0 },
      payment: { mode: "direct", agreed_cents: 8000, paid_cents: 0, pending_cents: 0, refunded_cents: 0, delivered_cents: 0 },
      movements: [] };
    const free: IncidentReview = { ...open, id: "i4", description: "Gratis", can_settle: false,
      opening: { ...open.opening, payment_mode: null, pending_cents: null }, payment: null, movements: [] };
    const api = fakeApi([direct, free]);
    render(<App api={api} language="es" />);
    await userEvent.click(await screen.findByRole("button", { name: es.sectionIncidents }));
    expect(await screen.findByText(es.moneyNone_direct)).toBeTruthy();
    expect(screen.getByText(es.moneyNone_free)).toBeTruthy();
    expect(screen.queryByLabelText(es.settle_deliver)).toBeNull();
  });

  it("executed decisions are shown with actor, date and reason and offer no actions", async () => {
    const resolved: IncidentReview = { ...open, status: "resolved", resolved_at: "2026-11-25T10:00:00Z", can_settle: false,
      settlement: "split", settlement_refunded_cents: 3000, settlement_delivered_cents: 5000, settlement_reason: "Parcial",
      settled_at: "2026-11-25T09:00:00Z", settled_by_email: "admin@example.org", performance_decision: "performed",
      performance_reason: "Fotos claras", performance_decided_at: "2026-11-25T10:00:00Z",
      performance_decided_by_email: "admin@example.org" };
    const api = fakeApi([resolved]);
    await openIncidents(api);
    expect(screen.getByText(/Devolver una parte y entregar el resto: devuelto 30,00\s€, entregado 50,00\s€.*Motivo: Parcial/)).toBeTruthy();
    expect(screen.getByText(/Consta realizado.*Motivo: Fotos claras/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: es.settleReview })).toBeNull();
    expect(screen.queryByRole("button", { name: es.performanceReview })).toBeNull();
  });

  it("shows a server refusal as an error, never as a confirmation", async () => {
    const api = fakeApi([open], { settleIncident: vi.fn(async () => { throw new AdminError("balance_changed"); }) });
    await openIncidents(api);
    const money = screen.getByRole("group", { name: es.moneyTitle });
    await userEvent.click(within(money).getByLabelText(es.settle_refund));
    await userEvent.type(within(money).getByLabelText(es.decisionReason), "No hubo trabajo");
    await userEvent.click(within(money).getByRole("button", { name: es.settleReview }));
    await userEvent.click(within(money).getByRole("button", { name: es.settleConfirm }));
    expect(await screen.findByText(es.error_balance_changed)).toBeTruthy();
    expect(screen.queryByText(es.settledNotice)).toBeNull();
  });
});
