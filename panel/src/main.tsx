import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createSupabaseAdminApi } from "./api";
import "./style.css";

// Public build-time values only (Cloudflare Pages environment). Never a secret or service key.
const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined;
const configured = !!url && !!key && key.startsWith("sb_publishable_") &&
  (url.startsWith("https://") || /^http:\/\/(127\.0\.0\.1|localhost):54321$/.test(url));

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App api={configured ? createSupabaseAdminApi(url!, key!) : null} />
  </StrictMode>,
);
