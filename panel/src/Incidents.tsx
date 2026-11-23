// Incident review (#31). The administrator sees only what the server returns for the incident (appointment data,
// accepted conditions, movements and contributed evidence) and takes two independent, final decisions with a reason:
// how the pending simulated balance is settled, and whether the service took place. Nothing here edits or reverts an
// executed decision, and no money action is offered when the panel controls no pending balance.
import { useCallback, useEffect, useState } from "react";
import { AdminError, type AdminApi, type Failure, type IncidentReview, type PerformanceDecision, type Settlement } from "./api";
import type { Language, MessageKey } from "./i18n";

type T = (key: MessageKey, values?: Record<string, string | number>) => string;
type View = "open" | "all";

function failureKey(error: unknown): MessageKey {
  const failure: Failure = error instanceof AdminError ? error.failure : "service";
  return `error_${failure}` as MessageKey;
}

/** "25,50" or "25.5" → 2550; null when it is not a plain euro amount. */
export function parseEuroCents(text: string): number | null {
  const match = text.trim().match(/^(\d{1,7})(?:[.,](\d{1,2}))?$/);
  if (!match) return null;
  return Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
}

export function IncidentReviews({ api, t, language }: { api: AdminApi; t: T; language: Language }) {
  const [view, setView] = useState<View>("open");
  const [items, setItems] = useState<IncidentReview[] | null>(null);
  const [error, setError] = useState<MessageKey | null>(null);
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const load = useCallback(async (which: View) => {
    setItems(null); setError(null);
    try { setItems(await api.incidents(which)); } catch (e) { setError(failureKey(e)); setItems([]); }
  }, [api]);
  useEffect(() => { void load(view); }, [load, view]);
  const date = (iso: string) => new Intl.DateTimeFormat(language, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  const euros = (cents: number) => new Intl.NumberFormat(language, { style: "currency", currency: "EUR" }).format(cents / 100);

  return (
    <section>
      <nav className="tabs">
        {(["open", "all"] as View[]).map((v) => (
          <button key={v} className={v === view ? "tab active" : "tab"} aria-pressed={v === view}
            onClick={() => { setNotice(null); setView(v); }}>{t(v === "open" ? "incidentsOpen" : "history")}</button>
        ))}
        <button className="secondary" onClick={() => void load(view)}>{t("refresh")}</button>
      </nav>
      {notice && <p role="status" className="ok">{t(notice)}</p>}
      {error && <p role="alert" className="error">{t(error)}</p>}
      {items === null ? <p role="status">{t("incidentsLoading")}</p>
        : items.length === 0 ? <p className="muted">{t("incidentsEmpty")}</p>
        : items.map((incident) => (
          <IncidentCard key={incident.id} incident={incident} api={api} t={t} date={date} euros={euros}
            onDecided={(done) => { setNotice(done); void load(view); }} />
        ))}
    </section>
  );
}

interface CardProps {
  incident: IncidentReview; api: AdminApi; t: T; date: (iso: string) => string; euros: (cents: number) => string;
  onDecided: (notice: MessageKey) => void;
}

function IncidentCard({ incident: i, api, t, date, euros, onDecided }: CardProps) {
  const a = i.appointment;
  const c = i.conditions;
  const p = i.payment;
  const stageKey = `stage_${a.stage}` as MessageKey;
  const included = [c?.materials_included && t("includedMaterials"), c?.travel_included && t("includedTravel")].filter(Boolean);
  const awaiting = i.status === "open" && ((i.settlement !== null) !== (i.performance_decision !== null));
  return (
    <article className="card">
      <h2>{t(`incidentKind_${i.kind}` as MessageKey)}</h2>
      <p className={i.status === "open" ? "ok" : "muted"}>
        {i.status === "open" ? t("statusOpen") : t("statusResolved", { date: date(i.resolved_at ?? i.created_at) })}</p>
      <p className="muted">{t("incidentOpenedBy", { role: t(`role_${i.opened_by_role}`), date: date(i.created_at) })}</p>
      <p className="quote">{i.description}</p>
      <p className="muted">{t(i.performance === "acknowledged" ? "performanceAcknowledged" : "performanceDisputed")}</p>
      <p className="muted small">{t("privacyScope")}</p>

      <h3>{t("appointmentTitle")}</h3>
      <dl>
        {a.profile_name && <><dt>{t("appointmentProfile")}</dt><dd>{a.profile_name}</dd></>}
        <dt>{t("appointmentProblem")}</dt><dd>{a.problem}</dd>
        <dt>{t("appointmentRequested")}</dt><dd>{date(a.requested_start)} – {date(a.requested_end)}</dd>
        <dt>{t("appointmentStage")}</dt><dd>{a.stage in stageLabels ? t(stageKey) : a.stage}
          {a.started_at && <div className="muted">{t("markStarted", { date: date(a.started_at) })}</div>}
          {a.done_at && <div className="muted">{t("markDone", { date: date(a.done_at) })}</div>}
          {a.completed_at && <div className="muted">{t(a.completion === "administration" ? "markCompletedAdministration"
            : "markCompleted", { date: date(a.completed_at) })}</div>}
        </dd>
      </dl>

      {c ? (<>
        <h3>{t("conditionsTitle", { version: c.version })}</h3>
        <dl>
          <dt>{t("conditionsScope")}</dt><dd>{c.scope}</dd>
          <dt>{t("conditionsStart")}</dt><dd>{date(c.start_at)}</dd>
          <dt>{t("conditionsDuration")}</dt><dd>{t("durationMinutes", { minutes: c.duration_minutes })}</dd>
          <dt>{t("conditionsAmount")}</dt><dd>{euros(c.amount_cents)}</dd>
          {c.visit_fee_cents !== null && <><dt>{t("conditionsVisitFee")}</dt><dd>{euros(c.visit_fee_cents)}</dd></>}
          <dt>{t("conditionsIncluded")}</dt><dd>{included.length ? included.join(", ") : t("includedNothing")}</dd>
        </dl>
      </>) : <p className="muted">{t("noConditions")}</p>}

      <h3>{t("paymentTitle")}</h3>
      {!p ? <p>{t("paymentFree")}</p>
        : p.mode === "direct" ? <p>{t("paymentDirect", { amount: euros(p.agreed_cents) })}</p>
        : <p>{t("paymentBalance", { paid: euros(p.paid_cents), pending: euros(p.pending_cents),
            refunded: euros(p.refunded_cents), delivered: euros(p.delivered_cents) })}</p>}
      {i.movements.length > 0 && <>
        <h4>{t("movementsTitle")}</h4>
        <ul>{i.movements.map((m, n) => <li key={n}>{t("movementLine", { kind: t(`movement_${m.kind}` as MessageKey),
          amount: euros(m.amount_cents), date: date(m.created_at), by: t(`by_${m.by}` as MessageKey) })}</li>)}</ul>
      </>}

      <h3>{t("evidenceTitle", { count: i.evidence.length })}</h3>
      {i.evidence.length === 0 ? <p className="muted">{t("evidenceNone")}</p>
        : <ul className="evidence">{i.evidence.map((e) => <Evidence key={e.id} evidence={e} api={api} t={t} date={date} />)}</ul>}

      <Money incident={i} api={api} t={t} date={date} euros={euros} onDecided={onDecided} />
      <PerformanceDecisionForm incident={i} api={api} t={t} date={date} euros={euros} onDecided={onDecided} />
      {awaiting && <p className="muted">{t("awaitingBoth")}</p>}
    </article>
  );
}

const stageLabels = { confirmed: 1, started: 1, done: 1, completed: 1, cancelled: 1 };

function Evidence({ evidence: e, api, t, date }: { evidence: IncidentReview["evidence"][number]; api: AdminApi; t: T;
  date: (iso: string) => string }) {
  const [busy, setBusy] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<MessageKey | null>(null);
  async function open(path: string) {
    setBusy(true); setError(null);
    try { setUrl(await api.evidenceUrl(path)); } catch (err) { setError(failureKey(err)); } finally { setBusy(false); }
  }
  const path = e.attachment_path;
  return (
    <li>
      <div className="muted small">{t("evidenceBy", { role: t(`role_${e.author_role}`), date: date(e.created_at) })}</div>
      {e.kind === "note" ? <div><strong>{t("evidenceNote")}:</strong> {e.note}</div> : <div>
        <strong>{t("evidenceMessage", { role: t(`role_${e.message_author_role ?? e.author_role}`) })}
          {e.message_created_at && <> · {date(e.message_created_at)}</>}:</strong>{" "}
        {path ? <>{t(e.attachment_kind === "video" ? "evidenceVideo" : "evidencePhoto")}{" "}
          {url ? (e.attachment_kind === "video"
            ? <video src={url} controls className="media" />
            : <img src={url} alt={t("evidencePhoto")} className="media" />)
            : <button className="secondary" disabled={busy} onClick={() => void open(path)}>
                {busy ? t("evidenceOpening") : t("evidenceOpen")}</button>}
        </> : e.message_body}
      </div>}
      {error && <p role="alert" className="error">{t(error)}</p>}
    </li>
  );
}

function Money({ incident: i, api, t, date, euros, onDecided }: CardProps) {
  const [outcome, setOutcome] = useState<Settlement | null>(null);
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  if (i.settlement) {
    return <section className="decide"><h3>{t("moneyTitle")}</h3><p className="ok">{t("settlementDone", {
      outcome: t(`settle_${i.settlement}`), refund: euros(i.settlement_refunded_cents ?? 0),
      deliver: euros(i.settlement_delivered_cents ?? 0), date: date(i.settled_at!), admin: i.settled_by_email ?? "—",
      reason: i.settlement_reason ?? "" })}</p></section>;
  }
  if (!i.can_settle || !i.payment) {
    const why: MessageKey = !i.payment ? "moneyNone_free" : i.payment.mode === "direct" ? "moneyNone_direct"
      : !i.opening.blocks_delivery || i.payment.delivered_cents > 0 && i.payment.pending_cents === 0 ? "moneyNone_delivered"
      : "moneyNone_other";
    return <section className="decide"><h3>{t("moneyTitle")}</h3><p className="muted">{t(why)}</p></section>;
  }
  const pending = i.payment.pending_cents;
  const splitCents = parseEuroCents(amount);
  const splitValid = splitCents !== null && splitCents > 0 && splitCents < pending;
  const refund = outcome === "deliver" ? 0 : outcome === "refund" ? pending : splitValid ? splitCents! : null;
  const ready = outcome !== null && refund !== null && reason.trim().length > 0;
  async function execute() {
    setBusy(true); setError(null);
    try {
      await api.settleIncident(i.id, outcome!, outcome === "split" ? refund : null, pending, reason.trim());
      onDecided("settledNotice");
    } catch (e) { setError(failureKey(e)); setConfirming(false); }
    finally { setBusy(false); }
  }
  return (
    <fieldset className="decide" aria-label={t("moneyTitle")} disabled={busy}>
      <legend>{t("moneyTitle")}</legend>
      <p className="muted">{t("moneyExplain", { pending: euros(pending) })}</p>
      {(["deliver", "refund", "split"] as Settlement[]).map((o) => (
        <label key={o} className="choice">
          <input type="radio" name={`settle-${i.id}`} checked={outcome === o} disabled={confirming}
            onChange={() => setOutcome(o)} />{t(`settle_${o}`)}
        </label>
      ))}
      {outcome === "split" && <label>{t("splitAmount")}
        <input inputMode="decimal" value={amount} disabled={confirming} onChange={(e) => setAmount(e.target.value)} /></label>}
      {outcome === "split" && amount.trim() !== "" && !splitValid && <p className="error">{t("splitInvalid")}</p>}
      <label>{t("decisionReason")}
        <textarea maxLength={2000} rows={2} value={reason} disabled={confirming} onChange={(e) => setReason(e.target.value)} />
      </label>
      {error && <p role="alert" className="error">{t(error)}</p>}
      {confirming && refund !== null
        ? <div className="confirm"><p>{t("settleSummary", { refund: euros(refund), deliver: euros(pending - refund) })}</p>
            <div className="actions">
              <button className="danger" onClick={() => void execute()}>{busy ? t("working") : t("settleConfirm")}</button>
              <button className="secondary" onClick={() => setConfirming(false)}>{t("back")}</button>
            </div></div>
        : <div className="actions"><button disabled={!ready} onClick={() => setConfirming(true)}>{t("settleReview")}</button></div>}
    </fieldset>
  );
}

function PerformanceDecisionForm({ incident: i, api, t, date, onDecided }: CardProps) {
  const [decision, setDecision] = useState<PerformanceDecision | null>(null);
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  if (i.performance_decision) {
    return <section className="decide"><h3>{t("performanceTitle")}</h3><p className="ok">{t("performanceDone", {
      decision: t(`decision_${i.performance_decision}`), date: date(i.performance_decided_at!),
      admin: i.performance_decided_by_email ?? "—", reason: i.performance_reason ?? "" })}</p></section>;
  }
  if (i.status !== "open") return null;
  async function execute() {
    setBusy(true); setError(null);
    try { await api.decidePerformance(i.id, decision!, reason.trim()); onDecided("decidedNotice"); }
    catch (e) { setError(failureKey(e)); setConfirming(false); }
    finally { setBusy(false); }
  }
  return (
    <fieldset className="decide" aria-label={t("performanceTitle")} disabled={busy}>
      <legend>{t("performanceTitle")}</legend>
      <p className="muted">{t("performanceExplain")}</p>
      {(["performed", "not_performed", "missing_evidence"] as PerformanceDecision[]).map((d) => (
        <label key={d} className="choice">
          <input type="radio" name={`performance-${i.id}`} checked={decision === d} disabled={confirming}
            onChange={() => setDecision(d)} />{t(`decision_${d}`)}
        </label>
      ))}
      <label>{t("decisionReason")}
        <textarea maxLength={2000} rows={2} value={reason} disabled={confirming} onChange={(e) => setReason(e.target.value)} />
      </label>
      {error && <p role="alert" className="error">{t(error)}</p>}
      {confirming && decision
        ? <div className="confirm"><p>{t("performanceSummary", { decision: t(`decision_${decision}`) })}</p>
            <div className="actions">
              <button className="danger" onClick={() => void execute()}>{busy ? t("working") : t("performanceConfirm")}</button>
              <button className="secondary" onClick={() => setConfirming(false)}>{t("back")}</button>
            </div></div>
        : <div className="actions"><button disabled={!decision || !reason.trim()} onClick={() => setConfirming(true)}>
            {t("performanceReview")}</button></div>}
    </fieldset>
  );
}
