import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminError, type Account, type AdminApi, type Failure, type Submission } from "./api";
import { initialLanguage, languageNames, languages, translate, type Language, type MessageKey } from "./i18n";

type View = "pending" | "all";

function failureKey(error: unknown): MessageKey {
  const failure: Failure = error instanceof AdminError ? error.failure : "service";
  return `error_${failure}` as MessageKey;
}

export function App({ api, language: start }: { api: AdminApi | null; language?: Language }) {
  const [language, setLanguage] = useState<Language>(start ?? initialLanguage);
  const t = (key: MessageKey, values?: Record<string, string | number>) => translate(language, key, values);
  const [account, setAccount] = useState<Account | null | undefined>(undefined);
  const [error, setError] = useState<MessageKey | null>(null);

  useEffect(() => { document.documentElement.lang = language; }, [language]);
  useEffect(() => {
    if (!api) return;
    api.account().then(setAccount, (e) => { setAccount(null); setError(failureKey(e)); });
  }, [api]);

  function changeLanguage(next: Language) {
    setLanguage(next);
    try { localStorage.setItem("teloarreglo-panel-language", next); } catch { /* optional convenience */ }
  }

  return (
    <div className="page">
      <header className="top">
        <div>
          <h1>{t("title")}</h1>
          <p className="muted">{t("subtitle")}</p>
        </div>
        <label className="language">
          <span>{t("language")}</span>
          <select aria-label={t("language")} value={language} onChange={(e) => changeLanguage(e.target.value as Language)}>
            {languages.map((l) => <option key={l} value={l} lang={l}>{languageNames[l]}</option>)}
          </select>
        </label>
      </header>
      <main>
        {!api ? <p role="alert" className="error">{t("configuration")}</p>
          : account === undefined ? <p role="status">{t("checking")}</p>
          : account === null ? <SignIn api={api} t={t} initialError={error} onSignedIn={setAccount} />
          : (
            <>
              <div className="session">
                <span>{t("signedInAs", { email: account.email })}</span>
                <button className="secondary" onClick={() => api.signOut().finally(() => setAccount(null))}>{t("signOut")}</button>
              </div>
              {account.is_administrator
                ? <Reviews api={api} t={t} language={language} />
                : <p role="alert" className="error">{t("notAdministrator")}</p>}
            </>
          )}
      </main>
    </div>
  );
}

type T = (key: MessageKey, values?: Record<string, string | number>) => string;

function SignIn({ api, t, initialError, onSignedIn }: { api: AdminApi; t: T; initialError: MessageKey | null; onSignedIn: (a: Account) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<MessageKey | null>(initialError);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError(null);
    try { onSignedIn(await api.signIn(email.trim(), password)); }
    catch (e) { setError(failureKey(e)); }
    finally { setPassword(""); setBusy(false); }
  }
  return (
    <form className="card narrow" onSubmit={submit}>
      <label>{t("email")}<input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} /></label>
      <label>{t("password")}<input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      {error && <p role="alert" className="error">{t(error)}</p>}
      <button type="submit" disabled={busy}>{busy ? t("working") : t("signIn")}</button>
    </form>
  );
}

function Reviews({ api, t, language }: { api: AdminApi; t: T; language: Language }) {
  const [view, setView] = useState<View>("pending");
  const [items, setItems] = useState<Submission[] | null>(null);
  const [error, setError] = useState<MessageKey | null>(null);
  const [notice, setNotice] = useState<MessageKey | null>(null);
  const load = useCallback(async (which: View) => {
    setItems(null); setError(null);
    try { setItems(await api.submissions(which)); } catch (e) { setError(failureKey(e)); setItems([]); }
  }, [api]);
  useEffect(() => { void load(view); }, [load, view]);
  const date = (iso: string) => new Intl.DateTimeFormat(language, { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));

  return (
    <section>
      <nav className="tabs">
        {(["pending", "all"] as View[]).map((v) => (
          <button key={v} className={v === view ? "tab active" : "tab"} aria-pressed={v === view}
            onClick={() => { setNotice(null); setView(v); }}>{t(v === "pending" ? "pending" : "history")}</button>
        ))}
        <button className="secondary" onClick={() => void load(view)}>{t("refresh")}</button>
      </nav>
      {notice && <p role="status" className="ok">{t(notice)}</p>}
      {error && <p role="alert" className="error">{t(error)}</p>}
      {items === null ? <p role="status">{t("loading")}</p>
        : items.length === 0 ? <p className="muted">{t("empty")}</p>
        : items.map((s) => (
          <SubmissionCard key={s.submission_id} submission={s} t={t} date={date} api={api}
            onDecided={(approved) => { setNotice(approved ? "approvedDone" : "rejectedDone"); void load(view); }} />
        ))}
    </section>
  );
}

function SubmissionCard({ submission: s, t, date, api, onDecided }: {
  submission: Submission; t: T; date: (iso: string) => string; api: AdminApi; onDecided: (approved: boolean) => void;
}) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<MessageKey | null>(null);
  const c = s.content;
  async function decide(approve: boolean) {
    setBusy(true); setError(null);
    try { await api.review(s.submission_id, approve, reason.trim()); onDecided(approve); }
    catch (e) { setError(failureKey(e)); }
    finally { setBusy(false); }
  }
  const field = (label: MessageKey, value: string | undefined | null) =>
    value ? <><dt>{t(label)}</dt><dd>{value}</dd></> : null;
  return (
    <article className="card">
      <h2>{c.name}</h2>
      <p className="muted">{t("submittedAt", { date: date(s.submitted_at), version: s.version })}</p>
      <p className="muted">{t(s.published ? "publishedNow" : "notPublished")}</p>
      {!s.latest && <p className="muted">{t("outdated")}</p>}
      <dl>
        {field("description", c.description)}
        {field("trades", c.trades.join(", "))}
        {field("services", c.services.join(", "))}
        {field("coverage", c.coverage.map((z) => z.name).join(", "))}
        {field("languages", c.languages.join(", "))}
        {field("phone", c.phone)}
        {field("businessAddress", c.business_address)}
        {field("prices", (c.prices ?? []).map((p) =>
          p.amount_cents == null ? p.service : `${p.service}: ${(p.amount_cents / 100).toFixed(2)} €`).join(" · "))}
      </dl>
      {s.decision ? (
        <p className={s.decision.approved ? "ok" : "error"}>{t("decision", {
          result: t(s.decision.approved ? "resultApproved" : "resultRejected"),
          date: date(s.decision.decided_at), admin: s.decision.administrator_email, reason: s.decision.reason })}</p>
      ) : s.status === "pending" && (
        <div className="decide">
          <label>{t("reason")}
            <textarea maxLength={2000} rows={3} value={reason} disabled={busy} onChange={(e) => setReason(e.target.value)} />
          </label>
          {error && <p role="alert" className="error">{t(error)}</p>}
          <div className="actions">
            <button disabled={busy || !reason.trim()} onClick={() => void decide(true)}>{busy ? t("working") : t("approve")}</button>
            <button className="danger" disabled={busy || !reason.trim()} onClick={() => void decide(false)}>{t("reject")}</button>
          </div>
        </div>
      )}
    </article>
  );
}
