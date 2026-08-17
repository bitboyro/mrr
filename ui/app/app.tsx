"use client";

import { FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";

const TOKEN_KEY = "mrr.token";

type Me = { userId: string; name: string; role: "ADMIN" | "USER" };
type Project = {
  id: string;
  name: string;
  enabled: boolean;
  gitlabUrl: string;
  gitlabProjectId: string;
  provider: "ANTHROPIC" | "OPENAI_COMPAT";
  modelBaseUrl: string | null;
  model: string;
  skill: string | null;
  skipDrafts: boolean;
  agentMaxTurns: number;
  agentTools: string[];
  autoApprove: boolean;
  librarianEnabled: boolean;
  memoryHeadSha: string | null;
  memoryStatus: string | null;
  memoryRefreshedAt: string | null;
  lastErrorKind: string | null;
  lastError: string | null;
  lastErrorAt: string | null;
};
type MemoryDocument = {
  id: string;
  projectId: string;
  kind: string;
  space: string;
  title: string;
  subject: string | null;
  tags: string[];
  status: string | null;
  sourceSha: string | null;
  mrIid: number | null;
  path: string;
  createdAt: string;
  updatedAt: string;
  body: string | null;
};
type MemoryRun = {
  id: string;
  projectId: string;
  kind: string;
  state: string;
  prompt: string | null;
  response: string | null;
  trace: AgentTrace | null;
  agentTurns: number;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
};
const AGENT_TOOLS = ["read_file", "search_memory", "read_memory", "write_memory"] as const;
type MrStatus = {
  projectId: string;
  mrIid: number;
  currentStatus: string;
  latestReviewId: string | null;
  latestHeadSha: string | null;
  openFindings: number;
  mrrApproved: boolean;
  userContext: string | null;
  updatedAt: string;
  title: string | null;
};
type MrPage = { items: MrStatus[]; total: number; offset: number; limit: number };
type Finding = {
  id: string;
  filePath: string | null;
  line: number | null;
  severity: string;
  title: string;
  body: string;
  state: string;
};
type Review = {
  id: string;
  projectId: string;
  mrIid: number;
  headSha: string;
  state: string;
  mr: MrSnapshot;
  prompt: string | null;
  response: string | null;
  error: string | null;
  errorKind: string | null;
  createdAt: string;
  agentTurns: number;
  trace: AgentTrace | null;
};
type MrSnapshot = {
  title: string | null;
  url: string | null;
  sourceBranch?: string | null;
  targetBranch?: string | null;
  authorName?: string | null;
  authorUsername?: string | null;
  actorName?: string | null;
  actorUsername?: string | null;
  commitAuthorName?: string | null;
  commitCount?: number | null;
  filesChanged?: number | null;
  assignees?: string | null;
  reviewers?: string | null;
};
type AgentTrace = {
  turns: number;
  maxTurns: number;
  events: TraceEvent[];
};
type TraceEvent = {
  role: string;
  turn: number;
  elapsedMs?: number | null;
  stopReason?: string | null;
  text?: string | null;
  toolCalls?: { id: string; name: string; arguments: string }[];
  id?: string | null;
  name?: string | null;
  result?: string | null;
  truncated?: boolean | null;
};
type Reply = {
  id: string;
  discussionId: string;
  findingId: string | null;
  prompt: string | null;
  response: string | null;
  trace: AgentTrace | null;
  createdAt: string;
};
type LoggerState = { name: string; configuredLevel: string | null; effectiveLevel: string };
type LogLine = { seq: number; at: string; level: string; logger: string; message: string; thrown: string | null };
type LogSnapshot = { lines: LogLine[]; buffered: number };
type Cycle = { status: MrStatus; reviews: Review[]; findings: Finding[]; replies: Reply[] };
type AgentSkill = {
  id: string;
  name: string;
  body: string;
  enabled: boolean;
  branchPattern: string | null;
  position: number;
};
type AgentDocument = {
  id: string;
  name: string;
  body: string;
  enabled: boolean;
};
type User = { id: string; name: string; role: string };
type TokenInfo = { id: string; name: string; prefix: string; createdAt: string; lastUsedAt: string | null };

type Route =
  | { name: "howto" }
  | { name: "projects" }
  | { name: "project"; id: string }
  | { name: "edit"; id: string }
  | { name: "new" }
  | { name: "mr"; id: string; iid: string }
  | { name: "review"; id: string }
  | { name: "admin" }
  | { name: "logs" };

function parseRoute(path: string): Route {
  if (path === "/" || path === "/login" || path === "/howto") return { name: "howto" };
  if (path === "/projects") return { name: "projects" };
  if (path === "/projects/new") return { name: "new" };
  if (path === "/admin") return { name: "admin" };
  if (path === "/logs") return { name: "logs" };
  let m = path.match(/^\/projects\/([^/]+)\/edit$/);
  if (m) return { name: "edit", id: m[1] };
  m = path.match(/^\/projects\/([^/]+)\/mrs\/(\d+)$/);
  if (m) return { name: "mr", id: m[1], iid: m[2] };
  m = path.match(/^\/projects\/([^/]+)$/);
  if (m) return { name: "project", id: m[1] };
  m = path.match(/^\/reviews\/([^/]+)$/);
  if (m) return { name: "review", id: m[1] };
  return { name: "howto" };
}

function go(path: string) {
  if (window.location.pathname === path) return;
  window.history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

const CAT_FRAMES = Array.from({ length: 21 }, (_, i) => `/cats/${String(i).padStart(2, "0")}.png`);
const LOGO_MS = 2000;
const CAT_MS = 2200;
function pickCat() {
  return CAT_FRAMES[Math.floor(Math.random() * CAT_FRAMES.length)];
}
function rollIntro() {
  return !window.matchMedia("(prefers-reduced-motion: reduce)").matches
    && Math.random() < 0.6;
}

/** 1s poll for pages whose data moves on its own (webhooks, reviews, librarian). */
function usePoll(fn: () => void, active: boolean) {
  const fnRef = useRef(fn);
  fnRef.current = fn;
  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => fnRef.current(), 1000);
    return () => window.clearInterval(timer);
  }, [active]);
}

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem(TOKEN_KEY) ?? "";
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`,
    ...(init.body ? { "Content-Type": "application/json" } : {}),
    ...(init.headers as Record<string, string> | undefined),
  };
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) {
    localStorage.removeItem(TOKEN_KEY);
    throw new Error("unauthorized");
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!res.ok) {
    try {
      const err = JSON.parse(text) as { message?: string };
      throw new Error(err.message || text);
    } catch (e) {
      if (e instanceof Error && e.message !== text) throw e;
      throw new Error(text || res.statusText);
    }
  }
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

function pill(status: string) {
  const s = status.toUpperCase();
  const cls = s === "CLEAN" || s === "READY" || s === "ADDRESSED" || s === "ENABLED" || s === "ON" || s === "MERGED" ? "ok"
    : s === "NEEDS_WORK" || s === "OPEN" || s === "REVIEWING" || s === "PENDING"
      || s === "PROCESSING" || s === "RUNNING" || s === "INITIALIZE" || s === "REFRESH" ? "warn"
    : s === "FAILED" || s === "CANCELLED" || s === "BLOCKER" || s === "QUOTA" || s === "PROVIDER"
      || s === "AUTH" || s === "MCP" || s === "GITLAB" || s === "RATE_LIMIT" || s === "REFUSAL" ? "bad"
    : s === "MAJOR" ? "major"
    : s === "MINOR" ? "minor"
    : "";
  return <span className={`pill ${cls}`}>{status}</span>;
}

function FailureBanner({ kind, message }: { kind?: string | null; message?: string | null }) {
  if (!kind && !message) return null;
  return (
    <div className="banner">
      <div className="row">
        {kind && pill(kind)}
        <strong>Nothing was posted to GitLab.</strong>
      </div>
      {message && <p>{message}</p>}
    </div>
  );
}

function Check({ on, onChange, children }: { on: boolean; onChange: () => void; children: ReactNode }) {
  return (
    <label className="check">
      <input type="checkbox" checked={on} onChange={onChange} />
      {children}
    </label>
  );
}

function Toggle({ on, onChange, children }: { on: boolean; onChange: (v: boolean) => void; children: ReactNode }) {
  return (
    <button type="button" className={`toggle${on ? " on" : ""}`} aria-pressed={on}
      onClick={() => onChange(!on)}>
      {children}
    </button>
  );
}

function Intro({ src, boot, onDone }: { src: string; boot?: boolean; onDone: () => void }) {
  const [phase, setPhase] = useState<"logo" | "cat">(boot ? "logo" : "cat");
  const done = useRef(onDone);
  done.current = onDone;
  useEffect(() => {
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced) {
      done.current();
      return;
    }
    const ms = phase === "logo" ? LOGO_MS : CAT_MS;
    const timer = window.setTimeout(() => {
      if (phase === "logo") setPhase("cat");
      else done.current();
    }, ms);
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") done.current(); };
    window.addEventListener("keydown", onKey);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("keydown", onKey);
    };
  }, [phase, src]);
  return (
    <div className={`intro ${phase}`} onClick={() => done.current()} role="presentation">
      <img key={phase} src={phase === "logo" ? "/logo.png" : src} alt={phase === "logo" ? "mrr" : ""} />
      <div className="intro-rule" />
    </div>
  );
}

function gitlabOrigin(url: string | null | undefined): string | null {
  if (!url) return null;
  try { return new URL(url).origin; } catch { return null; }
}

function gitlabReplaceMr(url: string, suffix: string): string {
  return url.replace(/\/-\/merge_requests\/\d+.*$/, suffix);
}

function parsePeople(block: string | null | undefined): { label: string; username: string | null }[] {
  if (!block) return [];
  return block.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const named = line.match(/^(.*?)\s*\(@([^)]+)\)$/);
    if (named) return { label: line, username: named[2] };
    if (line.startsWith("@")) return { label: line, username: line.slice(1) };
    return { label: line, username: null };
  });
}

function GitMeta({ mr, sha }: { mr?: MrSnapshot | null; sha?: string | null }) {
  if (!mr) return null;
  const origin = gitlabOrigin(mr.url);
  const branchHref = mr.url && mr.sourceBranch
    ? gitlabReplaceMr(mr.url, "/-/tree/" + encodeURIComponent(mr.sourceBranch))
    : null;
  const targetHref = mr.url && mr.targetBranch
    ? gitlabReplaceMr(mr.url, "/-/tree/" + encodeURIComponent(mr.targetBranch))
    : null;
  const commitsHref = mr.url ? mr.url.replace(/\/?$/, "") + "/commits" : null;
  const commitHref = mr.url && sha ? gitlabReplaceMr(mr.url, "/-/commit/" + sha) : null;

  type Chip = { role: string; label: string; href?: string; key: string };
  const chips: Chip[] = [];
  const seen = new Set<string>();
  function add(role: string, name: string | null | undefined, username: string | null | undefined) {
    if (username === "startup-scan") return;
    if (!name && !username) return;
    const key = (username || name || "").toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    const label = name && username && !name.includes("@" + username)
      ? `${name} (@${username})`
      : (name || "@" + username);
    chips.push({
      role,
      label,
      href: origin && username ? origin + "/" + encodeURIComponent(username) : undefined,
      key: role + ":" + key,
    });
  }
  add("author", mr.authorName, mr.authorUsername);
  add("pushed", mr.actorName, mr.actorUsername);
  add("commit", mr.commitAuthorName, null);
  for (const p of parsePeople(mr.assignees)) add("assignee", p.label, p.username);
  for (const p of parsePeople(mr.reviewers)) add("reviewer", p.label, p.username);

  const hasGit = !!(mr.sourceBranch || mr.commitCount != null || mr.filesChanged != null || sha);
  if (!hasGit && chips.length === 0) return null;

  return (
    <div className="git-meta">
      {mr.sourceBranch && (
        <span>
          {branchHref
            ? <a href={branchHref} target="_blank" rel="noreferrer">{mr.sourceBranch}</a>
            : mr.sourceBranch}
          {mr.targetBranch && (
            <>
              {" → "}
              {targetHref
                ? <a href={targetHref} target="_blank" rel="noreferrer">{mr.targetBranch}</a>
                : mr.targetBranch}
            </>
          )}
        </span>
      )}
      {mr.commitCount != null && (
        commitsHref
          ? <a href={commitsHref} target="_blank" rel="noreferrer">{mr.commitCount} {mr.commitCount === 1 ? "commit" : "commits"}</a>
          : <span>{mr.commitCount} {mr.commitCount === 1 ? "commit" : "commits"}</span>
      )}
      {sha && (
        commitHref
          ? <a href={commitHref} target="_blank" rel="noreferrer">{sha.slice(0, 8)}</a>
          : <span>{sha.slice(0, 8)}</span>
      )}
      {mr.filesChanged != null && (
        <span>{mr.filesChanged} {mr.filesChanged === 1 ? "file" : "files"}</span>
      )}
      {chips.length > 0 && (
        <span className="who">
          {chips.map((c) => (
            <span className="chip" key={c.key}>
              <span className="role">{c.role}</span>
              {c.href ? <a href={c.href} target="_blank" rel="noreferrer">{c.label}</a> : c.label}
            </span>
          ))}
        </span>
      )}
    </div>
  );
}

export default function App() {
  const [path, setPath] = useState("/");
  const [me, setMe] = useState<Me | null>(null);
  const [boot, setBoot] = useState(true);
  const [intro, setIntro] = useState({ show: true, src: pickCat(), key: 0, boot: true });

  useEffect(() => {
    setPath(window.location.pathname);
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setIntro((s) => ({ ...s, show: false }));
    }
    const onPop = () => {
      setPath(window.location.pathname);
      if (rollIntro()) {
        setIntro({ show: true, src: pickCat(), key: Date.now(), boot: false });
      } else {
        setIntro((s) => ({ ...s, show: false }));
      }
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      setBoot(false);
      return;
    }
    api<Me>("/api/me")
      .then(setMe)
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        setMe(null);
      })
      .finally(() => setBoot(false));
  }, []);

  const route = useMemo(() => parseRoute(path), [path]);

  let body: ReactNode = null;
  if (boot) {
    body = <div className="wrap muted">Loading…</div>;
  } else if (!me) {
    body = (
      <div className="wrap">
        <HowTo onLogin={setMe} />
      </div>
    );
  } else {
    body = (
      <div className={route.name === "logs" ? "wrap wide" : "wrap"}>
        <div className="top">
          <a href="/projects" className="brand" onClick={(e) => { e.preventDefault(); go("/projects"); }}>
            <img src="/logo.png" alt="mrr" />
          </a>
          <nav>
            <a href="/" onClick={(e) => { e.preventDefault(); go("/"); }}>How to</a>
            <a href="/projects" onClick={(e) => { e.preventDefault(); go("/projects"); }}>Projects</a>
            {me.role === "ADMIN" && (
              <>
                <a href="/logs" onClick={(e) => { e.preventDefault(); go("/logs"); }}>Logs</a>
                <a href="/admin" onClick={(e) => { e.preventDefault(); go("/admin"); }}>Admin</a>
              </>
            )}
          </nav>
          <span className="muted" style={{ marginLeft: "auto" }}>{me.name} · {me.role}</span>
          <button onClick={() => { localStorage.removeItem(TOKEN_KEY); setMe(null); go("/"); }}>
            Sign out
          </button>
        </div>
        {(route.name === "howto") && <HowTo />}
        {route.name === "projects" && <Projects />}
        {route.name === "new" && <ProjectForm />}
        {route.name === "edit" && <ProjectForm id={route.id} />}
        {route.name === "project" && <ProjectHome id={route.id} />}
        {route.name === "mr" && <MrCycle projectId={route.id} iid={route.iid} />}
        {route.name === "review" && <ReviewDetail id={route.id} />}
        {route.name === "admin" && me.role === "ADMIN" && <Admin />}
        {route.name === "logs" && me.role === "ADMIN" && <Logs />}
      </div>
    );
  }

  return (
    <>
      {body}
      {intro.show && (
        <Intro key={intro.key} src={intro.src} boot={intro.boot}
          onDone={() => setIntro((s) => ({ ...s, show: false }))} />
      )}
    </>
  );
}

function TokenForm({ onLogin }: { onLogin: (me: Me) => void }) {
  const [token, setToken] = useState("");
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    localStorage.setItem(TOKEN_KEY, token.trim());
    try {
      const me = await api<Me>("/api/me");
      onLogin(me);
    } catch {
      localStorage.removeItem(TOKEN_KEY);
      setErr("That token was rejected.");
    }
  }

  return (
    <form className="token-form" onSubmit={submit}>
      <p className="muted">Paste an API token. There is no password login.</p>
      <label>Token</label>
      <input value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" />
      {err && <p className="err">{err}</p>}
      <p><button className="primary" type="submit">Continue</button></p>
    </form>
  );
}

function HowTo({ onLogin }: { onLogin?: (me: Me) => void }) {
  return (
    <div className="howto">
      {onLogin && <img src="/logo.png" alt="mrr" className="login-mark" />}
      <p className="tag">Is it mr. reviewer? Or merge request reviewer? Nobody knows for sure.</p>
      <h1>How to set up mrr</h1>
      <p className="lead">
        The service is running. This is everything after that: a token, a GitLab project, a webhook,
        then what a review looks like.
      </p>
      {onLogin && <TokenForm onLogin={onLogin} />}

      <h2>1. Root token</h2>
      <pre className="token-box">{`┌─ mrr  root admin token ────────────────────────────────────┐
│  mrr_…                                                     │
└────────────────────────────────────────────────────────────┘`}</pre>
      <p>
        Printed <strong>once</strong> on first boot. Also <code>/data/root-token.txt</code>.
        DB stores a hash.{onLogin ? " Paste it above." : ""}
      </p>

      <h2>2. Users</h2>
      <p>Optional. Admin → one USER token per person. Don’t share root.</p>

      <h2>3. New project</h2>
      <p>
        <strong>Projects → New Project.</strong> GitLab URL, project id or{" "}
        <code>group/project</code>, token.
      </p>
      <table>
        <thead><tr><th>Token</th><th></th></tr></thead>
        <tbody>
          <tr>
            <td>Project access token</td>
            <td>Preferred — bot user. Developer + <code>api</code>. gitlab.com: Premium/Ultimate. Self-hosted Free: yes.</td>
          </tr>
          <tr><td>Group access token</td><td>Same as project.</td></tr>
          <tr><td>Fine-grained PAT</td><td>One project. Permissions below.</td></tr>
          <tr><td>Classic PAT</td><td><code>api</code>. User must be Developer on the project.</td></tr>
        </tbody>
      </table>
      <p className="muted">Not deploy tokens or CI job tokens. mrr sends <code>PRIVATE-TOKEN</code>.</p>

      <h3>Fine-grained PAT — always</h3>
      <table>
        <thead><tr><th>Permission</th><th></th></tr></thead>
        <tbody>
          <tr><td>Merge Request: Read</td><td>List, metadata, diff</td></tr>
          <tr><td>Merge Request: Create</td><td>Post overview and finding threads, reply</td></tr>
          <tr><td>Merge Request: Update</td><td>Edit overview, resolve / reopen</td></tr>
        </tbody>
      </table>
      <h3>If the feature is on</h3>
      <table>
        <thead><tr><th>Permission</th><th></th></tr></thead>
        <tbody>
          <tr><td>Merge Request: Approve</td><td>Auto-approve. Token identity must also be allowed to approve (rules, CODEOWNERS).</td></tr>
          <tr><td>Repository: Read</td><td><code>read_file</code>, librarian</td></tr>
          <tr><td>Commit: Read</td><td>Librarian tip SHA</td></tr>
          <tr><td>Project: Read</td><td>Librarian metadata</td></tr>
        </tbody>
      </table>
      <p>
        Do not grant Merge, repo write, Code: Push, Code: Read, or webhook scopes.
        mrr never pushes, merges, or creates the hook.
      </p>
      <p className="muted">
        Provider: <code>ANTHROPIC</code> or <code>OPENAI_COMPAT</code> (set base URL for Ollama / vLLM).
        First review: max turns 1, tools off.
      </p>

      <h2>4. Webhook</h2>
      <p>
        After Create, copy URL + secret <strong>once</strong> → GitLab <em>Settings → Webhooks</em>.
      </p>
      <ul>
        <li>Merge request events</li>
        <li>Comments — if mrr should answer replies on its threads</li>
      </ul>
      <p>
        <code>MRR_BASE_URL</code> must be reachable from GitLab. If it changes, edit the existing
        hook — two hooks double-fire.
      </p>

      <h2>5. Review</h2>
      <p>Open an MR, or wait — boot scans open MRs. UI polls.</p>
      <ul>
        <li>One <code>### mrr review</code> overview, edited in place</li>
        <li>One thread per finding</li>
        <li>Later commits: addressed resolve; still-open stay (reopened if resolved by hand); new problems get new threads</li>
      </ul>
      <p className="muted">
        Resolving in GitLab does not mark addressed in mrr. Title/label edits without a new SHA
        don’t spend tokens. Drafts are reviewed unless Skip drafts is on.
      </p>

      <h2>6. Optional</h2>
      <ul>
        <li>Skills / documents — Reviewer tab, next review</li>
        <li>Memory card written on READY. Reviewer reads it only with <code>search_memory</code> / <code>read_memory</code></li>
        <li>Librarian (off) — Initialize / Refresh of <code>project/</code> only; disable never deletes files</li>
        <li>Auto-approve — needs Merge Request: Approve</li>
      </ul>

      <h2>7. Nothing on GitLab</h2>
      <p>
        FAILED (quota, rate limit, unreachable, refusal) → mrr does <strong>not</strong> post. Re-run.
        Restart leaves in-flight rows PROCESSING — re-run those too.
        Secrets are plaintext in SQLite under <code>/data</code>.
      </p>
    </div>
  );
}

function Projects() {
  const [rows, setRows] = useState<Project[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api<Project[]>("/api/projects").then(setRows).catch((e) => setErr(String(e.message)));
  }, []);

  if (err) return <p className="err">{err}</p>;
  if (!rows) return <p className="muted">Loading…</p>;

  return (
    <div>
      <div className="page-head">
        <h2>Projects</h2>
        <button className="primary" onClick={() => go("/projects/new")}>New Project</button>
      </div>
      {rows.length === 0 && <p className="muted">None yet.</p>}
      {rows.map((p) => (
        <div className="card" key={p.id}>
          <div className="row">
            <a href={`/projects/${p.id}`} onClick={(e) => { e.preventDefault(); go(`/projects/${p.id}`); }}>
              <strong>{p.name}</strong>
            </a>
            {pill(p.enabled ? "enabled" : "disabled")}
            {p.autoApprove && <span className="pill ok">auto-approve</span>}
            {p.librarianEnabled && <span className="pill">librarian</span>}
            {p.lastErrorKind && pill(p.lastErrorKind)}
            <span className="muted">{p.gitlabProjectId}</span>
            <span className="muted" style={{ marginLeft: "auto" }}>{p.model}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

function ProjectHome({ id }: { id: string }) {
  const [project, setProject] = useState<Project | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [tab, setTab] = useState<"mrs" | "memory" | "reviewer">("mrs");

  function load() {
    api<Project>(`/api/projects/${id}`).then(setProject).catch((e) => setErr(String(e.message)));
  }
  useEffect(load, [id]);
  usePoll(load, true);

  if (err) return <p className="err">{err}</p>;
  if (!project) return <p className="muted">Loading…</p>;

  return (
    <div>
      <div className="page-head">
        <h2>{project.name}</h2>
        <button onClick={() => go(`/projects/${id}/edit`)}>Edit</button>
      </div>
      <p className="muted">
        {project.gitlabUrl}/{project.gitlabProjectId} · {project.model}
        {project.autoApprove ? " · auto-approve on" : ""}
        {project.librarianEnabled ? " · librarian on" : ""}
        {project.memoryStatus && project.memoryStatus !== "NONE" ? ` · memory ${project.memoryStatus}` : ""}
        {project.memoryHeadSha ? ` · ${project.memoryHeadSha.slice(0, 8)}` : ""}
        {" · "}turns {project.agentMaxTurns}
        {project.agentTools.length ? ` · tools ${project.agentTools.join(",")}` : ""}
      </p>
      <FailureBanner kind={project.lastErrorKind} message={project.lastError} />
      <div className="tabs">
        <button className={tab === "mrs" ? "on" : ""} onClick={() => setTab("mrs")}>Merge requests</button>
        <button className={tab === "memory" ? "on" : ""} onClick={() => setTab("memory")}>Memory</button>
        <button className={tab === "reviewer" ? "on" : ""} onClick={() => setTab("reviewer")}>Reviewer</button>
      </div>
      {tab === "mrs" && <MrList projectId={id} />}
      {tab === "memory" && <MemoryPanel project={project} onRefresh={load} />}
      {tab === "reviewer" && <AgentContext projectId={id} tools={project.agentTools} />}
    </div>
  );
}

const MR_STATUSES = ["REVIEWING", "NEEDS_WORK", "CLEAN", "FAILED", "CANCELLED", "MERGED", "CLOSED"];
const MR_PAGE = 40;

function MrList({ projectId }: { projectId: string }) {
  const [status, setStatus] = useState("");
  const [qLive, setQLive] = useState("");
  const [q, setQ] = useState("");
  const [items, setItems] = useState<MrStatus[]>([]);
  const [total, setTotal] = useState(0);
  const [busy, setBusy] = useState(false);
  const sentinel = useRef<HTMLDivElement>(null);
  const itemsRef = useRef(items);
  itemsRef.current = items;

  useEffect(() => {
    const timer = window.setTimeout(() => setQ(qLive), 200);
    return () => window.clearTimeout(timer);
  }, [qLive]);

  function query(offset: number, limit: number) {
    const p = new URLSearchParams({ offset: String(offset), limit: String(limit) });
    if (status) p.set("status", status);
    if (q.trim()) p.set("q", q.trim());
    return `/api/projects/${projectId}/merge-requests?${p}`;
  }

  async function refresh() {
    const n = Math.max(itemsRef.current.length, MR_PAGE);
    const page = await api<MrPage>(query(0, n));
    setItems(page.items);
    setTotal(page.total);
  }

  async function loadMore() {
    if (busy || itemsRef.current.length >= total) return;
    setBusy(true);
    try {
      const page = await api<MrPage>(query(itemsRef.current.length, MR_PAGE));
      setItems((prev) => {
        const seen = new Set(prev.map((m) => m.mrIid));
        return [...prev, ...page.items.filter((m) => !seen.has(m.mrIid))];
      });
      setTotal(page.total);
    } finally {
      setBusy(false);
    }
  }
  const loadMoreRef = useRef(loadMore);
  loadMoreRef.current = loadMore;

  useEffect(() => {
    setItems([]);
    setTotal(0);
    api<MrPage>(query(0, MR_PAGE)).then((page) => {
      setItems(page.items);
      setTotal(page.total);
    });
  }, [projectId, status, q]);

  usePoll(() => { refresh().catch(() => {}); }, true);

  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const obs = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) loadMoreRef.current();
    }, { rootMargin: "200px" });
    obs.observe(el);
    return () => obs.disconnect();
  }, []);

  return (
    <div>
      <div className="mr-filters">
        <input
          value={qLive}
          onChange={(e) => setQLive(e.target.value)}
          placeholder="Filter by title or !iid"
        />
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">All statuses</option>
          {MR_STATUSES.map((s) => <option key={s} value={s}>{s.replaceAll("_", " ")}</option>)}
        </select>
        <span className="muted">{total} merge request{total === 1 ? "" : "s"}</span>
      </div>
      {items.length === 0 && <p className="muted">{busy ? "Loading…" : "No merge requests match."}</p>}
      {items.map((mr) => (
        <a
          key={mr.mrIid}
          className={`mr-row st-${mr.currentStatus.toLowerCase()}`}
          href={`/projects/${projectId}/mrs/${mr.mrIid}`}
          onClick={(e) => { e.preventDefault(); go(`/projects/${projectId}/mrs/${mr.mrIid}`); }}
        >
          <span className="iid">!{mr.mrIid}</span>
          <span className="title">{mr.title || "Untitled"}</span>
          <span>{pill(mr.currentStatus)}</span>
          <span className={mr.openFindings > 0 ? "count-open" : "muted"}>{mr.openFindings} open</span>
        </a>
      ))}
      <div ref={sentinel} className="mr-sentinel" />
      {busy && items.length > 0 && <p className="muted">Loading…</p>}
    </div>
  );
}

function AgentContext({ projectId, tools }: { projectId: string; tools: string[] }) {
  const [skills, setSkills] = useState<AgentSkill[] | null>(null);
  const [docs, setDocs] = useState<AgentDocument[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [draftSkill, setDraftSkill] = useState({ name: "", body: "", branchPattern: "" });
  const [draftDoc, setDraftDoc] = useState({ name: "", body: "" });
  const [editing, setEditing] = useState<{ type: "skill" | "doc"; id: string; body: string; branchPattern?: string } | null>(null);

  function load() {
    Promise.all([
      api<AgentSkill[]>(`/api/projects/${projectId}/skills`),
      api<AgentDocument[]>(`/api/projects/${projectId}/documents`),
    ]).then(([s, d]) => { setSkills(s); setDocs(d); })
      .catch((e) => setErr(String(e.message)));
  }
  useEffect(load, [projectId]);

  async function toggleSkill(s: AgentSkill) {
    setSkills((list) => list?.map((x) => x.id === s.id ? { ...x, enabled: !x.enabled } : x) ?? null);
    try {
      await api(`/api/projects/${projectId}/skills/${s.id}`, {
        method: "PUT", body: JSON.stringify({ enabled: !s.enabled }),
      });
    } catch (e) {
      setErr(String((e as Error).message));
      load();
    }
  }
  async function toggleDoc(d: AgentDocument) {
    setDocs((list) => list?.map((x) => x.id === d.id ? { ...x, enabled: !x.enabled } : x) ?? null);
    try {
      await api(`/api/projects/${projectId}/documents/${d.id}`, {
        method: "PUT", body: JSON.stringify({ enabled: !d.enabled }),
      });
    } catch (e) {
      setErr(String((e as Error).message));
      load();
    }
  }
  async function addSkill(e: FormEvent) {
    e.preventDefault();
    await api(`/api/projects/${projectId}/skills`, {
      method: "POST", body: JSON.stringify({ ...draftSkill, enabled: true }),
    });
    setDraftSkill({ name: "", body: "", branchPattern: "" });
    load();
  }
  async function addDoc(e: FormEvent) {
    e.preventDefault();
    await api(`/api/projects/${projectId}/documents`, {
      method: "POST", body: JSON.stringify({ ...draftDoc, enabled: true }),
    });
    setDraftDoc({ name: "", body: "" });
    load();
  }
  async function saveEdit() {
    if (!editing) return;
    const path = editing.type === "skill"
      ? `/api/projects/${projectId}/skills/${editing.id}`
      : `/api/projects/${projectId}/documents/${editing.id}`;
    const body = editing.type === "skill"
      ? { body: editing.body, branchPattern: editing.branchPattern ?? "" }
      : { body: editing.body };
    await api(path, { method: "PUT", body: JSON.stringify(body) });
    setEditing(null);
    load();
  }
  async function remove(type: "skill" | "doc", id: string) {
    const path = type === "skill"
      ? `/api/projects/${projectId}/skills/${id}`
      : `/api/projects/${projectId}/documents/${id}`;
    await api(path, { method: "DELETE" });
    load();
  }

  if (err) return <p className="err">{err}</p>;
  if (!skills || !docs) return <p className="muted">Loading reviewer config…</p>;

  const pickedSkill = editing?.type === "skill" ? skills.find((s) => s.id === editing.id) : null;
  const pickedDoc = editing?.type === "doc" ? docs.find((d) => d.id === editing.id) : null;

  return (
    <div>
      <p className="muted">
        For the merge-request reviewer, not the librarian. Enabled skills go into the system
        prompt; enabled documents into the user prompt. A skill with a branch pattern only
        fires when the MR source branch matches — blank means every branch.
        {tools.length ? ` Tools on: ${tools.join(", ")}.` : " No tools — set them under Edit."}
      </p>
      <div className="skill-split">
        <div className="skill-list">
          <h2>Skills</h2>
          {skills.length === 0 && <p className="muted">None yet — the built-in reviewer prompt is used.</p>}
          {skills.map((s) => (
            <button
              key={s.id}
              type="button"
              className={`pick${editing?.type === "skill" && editing.id === s.id ? " on" : ""}`}
              onClick={() => setEditing({ type: "skill", id: s.id, body: s.body, branchPattern: s.branchPattern ?? "" })}
            >
              <span className="row">
                <strong>{s.name}</strong>
                {pill(s.enabled ? "on" : "off")}
              </span>
              <span className="muted">{s.branchPattern || "every branch"}</span>
            </button>
          ))}
          <h2>Documents</h2>
          {docs.length === 0 && <p className="muted">Folder is empty.</p>}
          {docs.map((d) => (
            <button
              key={d.id}
              type="button"
              className={`pick${editing?.type === "doc" && editing.id === d.id ? " on" : ""}`}
              onClick={() => setEditing({ type: "doc", id: d.id, body: d.body })}
            >
              <span className="row">
                <strong>{d.name}</strong>
                {pill(d.enabled ? "on" : "off")}
              </span>
            </button>
          ))}
        </div>
        <div>
          {pickedSkill && editing?.type === "skill" && (
            <div>
              <div className="editor-head">
                <h2>{pickedSkill.name}</h2>
                <div className="editor-actions">
                  <Check on={pickedSkill.enabled} onChange={() => toggleSkill(pickedSkill)}>Enabled</Check>
                  <button className="danger" onClick={() => { remove("skill", pickedSkill.id); setEditing(null); }}>Delete</button>
                </div>
              </div>
              <label>Branch pattern</label>
              <input placeholder="blank = every branch; hotfix/* or release-\d+"
                value={editing.branchPattern ?? ""}
                onChange={(e) => setEditing({ ...editing, branchPattern: e.target.value })} />
              <label>Instructions</label>
              <textarea value={editing.body} onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
              <p className="row">
                <button className="primary" onClick={saveEdit}>Save</button>
                <button onClick={() => setEditing(null)}>Cancel</button>
              </p>
            </div>
          )}
          {pickedDoc && editing?.type === "doc" && (
            <div>
              <div className="editor-head">
                <h2>{pickedDoc.name}</h2>
                <div className="editor-actions">
                  <Check on={pickedDoc.enabled} onChange={() => toggleDoc(pickedDoc)}>Enabled</Check>
                  <button className="danger" onClick={() => { remove("doc", pickedDoc.id); setEditing(null); }}>Delete</button>
                </div>
              </div>
              <label>Contents</label>
              <textarea value={editing.body} onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
              <p className="row">
                <button className="primary" onClick={saveEdit}>Save</button>
                <button onClick={() => setEditing(null)}>Cancel</button>
              </p>
            </div>
          )}
          {!editing && (
            <p className="muted">Select a skill or document, or add one below.</p>
          )}
          <form onSubmit={addSkill}>
            <label>Add skill</label>
            <div className="grid">
              <input placeholder="name (e.g. security)" value={draftSkill.name}
                onChange={(e) => setDraftSkill({ ...draftSkill, name: e.target.value })} required />
              <input placeholder="branch (optional): hotfix/*"
                value={draftSkill.branchPattern}
                onChange={(e) => setDraftSkill({ ...draftSkill, branchPattern: e.target.value })} />
            </div>
            <textarea placeholder="instructions" value={draftSkill.body}
              onChange={(e) => setDraftSkill({ ...draftSkill, body: e.target.value })} />
            <p className="row"><button className="primary" type="submit">Add</button></p>
          </form>
          <form onSubmit={addDoc}>
            <label>Add document</label>
            <div className="grid">
              <input placeholder="name (e.g. architecture.md)" value={draftDoc.name}
                onChange={(e) => setDraftDoc({ ...draftDoc, name: e.target.value })} required />
              <button className="primary" type="submit">Add</button>
            </div>
            <textarea placeholder="contents" value={draftDoc.body}
              onChange={(e) => setDraftDoc({ ...draftDoc, body: e.target.value })} />
          </form>
        </div>
      </div>
    </div>
  );
}

function MemoryPanel({ project, onRefresh }: { project: Project; onRefresh: () => void }) {
  const [docs, setDocs] = useState<MemoryDocument[] | null>(null);
  const [runs, setRuns] = useState<MemoryRun[] | null>(null);
  const [current, setCurrent] = useState<MemoryRun | null>(null);
  const [open, setOpen] = useState<MemoryDocument | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const watchId = useRef<string | null>(null);

  async function loadTree() {
    setDocs(await api<MemoryDocument[]>(`/api/projects/${project.id}/memory`));
  }
  async function loadRuns() {
    const list = await api<MemoryRun[]>(`/api/projects/${project.id}/memory/runs`);
    setRuns(list);
    const live = list.find((r) => r.state === "RUNNING" || r.state === "PENDING");
    const id = live?.id ?? watchId.current ?? list[0]?.id;
    if (!id) {
      setCurrent(null);
      return;
    }
    const full = await api<MemoryRun>(`/api/projects/${project.id}/memory/runs/${id}`);
    setCurrent(full);
    if (full.state !== "RUNNING" && full.state !== "PENDING") {
      watchId.current = null;
    }
  }

  function loadAll() {
    Promise.resolve()
      .then(loadTree)
      .then(loadRuns)
      .then(loadTree)
      .catch((e) => setErr(String(e.message)));
  }
  useEffect(loadAll, [project.id]);
  usePoll(loadAll, true);

  async function openDoc(doc: MemoryDocument) {
    if (open?.id === doc.id) {
      setOpen(null);
      return;
    }
    const full = await api<MemoryDocument>(`/api/projects/${project.id}/memory/documents/${doc.id}`);
    setOpen(full);
  }

  async function startLibrarian() {
    setBusy(true);
    setErr(null);
    try {
      const run = await api<MemoryRun>(`/api/projects/${project.id}/memory/refresh`, { method: "POST" });
      watchId.current = run.id;
      setCurrent(run);
      onRefresh();
      loadAll();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const spaces = ["project", "mrs", "notes"] as const;
  const hasProject = (docs ?? []).some((d) => d.space === "project");
  const since = current?.createdAt;
  const running = project.memoryStatus === "RUNNING"
    || current?.state === "RUNNING" || current?.state === "PENDING";

  return (
    <div>
      <div className="page-head">
        <h2>Memory</h2>
        {project.memoryStatus && project.memoryStatus !== "NONE" && pill(project.memoryStatus)}
        {project.librarianEnabled && (
          <button className="primary" disabled={busy || running} onClick={startLibrarian}>
            {hasProject ? "Refresh" : "Initialize"}
          </button>
        )}
      </div>
      <p className="muted">
        Reviewer-written MR cards, plus project overview if the librarian is on.
      </p>
      {err && <p className="err">{err}</p>}
      {!docs && <p className="muted">Loading memory…</p>}
      {docs && docs.length === 0 && <p className="muted">No documents yet. A review will write the first MR card.</p>}
      {spaces.map((space) => {
        const rows = (docs ?? []).filter((d) => d.space === space);
        if (rows.length === 0) return null;
        return (
          <div key={space}>
            <p className="muted" style={{ marginBottom: 4 }}>{space}/</p>
            {rows.map((d) => {
              const fresh = since && d.updatedAt >= since;
              return (
                <div className={`item${fresh ? " fresh" : ""}`} key={d.id}>
                  <div className="row">
                    <button aria-expanded={open?.id === d.id} onClick={() => openDoc(d)}>{d.path}</button>
                    {d.status && pill(d.status)}
                    {fresh && <span className="pill ok">new</span>}
                    <span className="muted">{d.title}</span>
                    {d.tags?.length ? <span className="muted">{d.tags.join(", ")}</span> : null}
                    {d.mrIid != null && (
                      <a href={`/projects/${project.id}/mrs/${d.mrIid}`}
                        onClick={(e) => { e.preventDefault(); go(`/projects/${project.id}/mrs/${d.mrIid}`); }}>
                        !{d.mrIid}
                      </a>
                    )}
                  </div>
                  {open?.id === d.id && (
                    <pre>{open.body || "(empty)"}</pre>
                  )}
                </div>
              );
            })}
          </div>
        );
      })}
      {current && (
        <div className="card">
          <div className="row">
            {pill(current.kind)}
            {pill(current.state)}
            {running && <span className="log-live" title="running" />}
            <span className="muted">{current.agentTurns} turn(s)</span>
            {current.finishedAt && <span className="muted">{current.finishedAt}</span>}
          </div>
          {current.error && <FailureBanner message={current.error} />}
          <details className="fold">
            <summary>Run log</summary>
            {current.prompt && (
              <details className="fold">
                <summary>Prompt</summary>
                <pre>{current.prompt}</pre>
              </details>
            )}
            <Trail trace={current.trace} />
            {current.response && (
              <details className="fold">
                <summary>Response</summary>
                <pre>{current.response}</pre>
              </details>
            )}
          </details>
        </div>
      )}
      {runs && runs.length > 1 && (
        <>
          <h2>Librarian history</h2>
          <table>
            <thead><tr><th>When</th><th>Kind</th><th>Turns</th><th>State</th></tr></thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.id}>
                  <td>
                    <button onClick={() => {
                      watchId.current = r.id;
                      api<MemoryRun>(`/api/projects/${project.id}/memory/runs/${r.id}`).then(setCurrent);
                    }}>{r.createdAt}</button>
                  </td>
                  <td>{pill(r.kind)}</td>
                  <td className="muted">{r.agentTurns}</td>
                  <td>{pill(r.state)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

function ProjectForm({ id }: { id?: string }) {
  const [form, setForm] = useState({
    name: "", gitlabUrl: "https://gitlab.com", gitlabProjectId: "", gitlabToken: "",
    provider: "ANTHROPIC", modelBaseUrl: "", model: "", modelKey: "", skill: "",
    skipDrafts: false, agentMaxTurns: 1, agentTools: "", autoApprove: false,
    librarianEnabled: false, enabled: true,
  });
  const [secret, setSecret] = useState<{ webhookUrl: string; webhookSecret: string } | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    api<Project>(`/api/projects/${id}`).then((p) => setForm((f) => ({
      ...f,
      name: p.name,
      gitlabUrl: p.gitlabUrl,
      gitlabProjectId: p.gitlabProjectId,
      provider: p.provider,
      modelBaseUrl: p.modelBaseUrl ?? "",
      model: p.model,
      skill: p.skill ?? "",
      skipDrafts: p.skipDrafts,
      agentMaxTurns: p.agentMaxTurns,
      agentTools: p.agentTools.join(","),
      autoApprove: p.autoApprove,
      librarianEnabled: p.librarianEnabled,
      enabled: p.enabled,
    }))).catch((e) => setErr(String(e.message)));
  }, [id]);

  function set<K extends keyof typeof form>(key: K, value: (typeof form)[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    const tools = form.agentTools.split(",").map((s) => s.trim()).filter(Boolean);
    const body: Record<string, unknown> = {
      name: form.name,
      gitlabUrl: form.gitlabUrl,
      gitlabProjectId: form.gitlabProjectId,
      provider: form.provider,
      model: form.model,
      skill: form.skill || null,
      skipDrafts: form.skipDrafts,
      agentMaxTurns: Number(form.agentMaxTurns) || 1,
      agentTools: tools,
      autoApprove: form.autoApprove,
      librarianEnabled: form.librarianEnabled,
      enabled: form.enabled,
    };
    if (form.gitlabToken) body.gitlabToken = form.gitlabToken;
    if (form.modelKey) body.modelKey = form.modelKey;
    if (form.modelBaseUrl) body.modelBaseUrl = form.modelBaseUrl;
    try {
      if (id) {
        await api(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) });
        go(`/projects/${id}`);
      } else {
        if (!form.gitlabToken || !form.modelKey) {
          setErr("GitLab token and model key are required.");
          return;
        }
        const created = await api<{ project: Project; webhookUrl: string; webhookSecret: string }>(
          "/api/projects", { method: "POST", body: JSON.stringify(body) });
        setSecret({ webhookUrl: created.webhookUrl, webhookSecret: created.webhookSecret });
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  if (secret) {
    return (
      <div>
        <h2>Paste these into GitLab</h2>
        <p className="muted">The webhook secret is shown once. Tick Merge request events, and Comments if you want replies.</p>
        <label>Webhook URL</label>
        <input readOnly value={secret.webhookUrl} />
        <label>Secret token</label>
        <input readOnly value={secret.webhookSecret} />
        <p><button className="primary" onClick={() => go("/projects")}>Done</button></p>
      </div>
    );
  }

  return (
    <form onSubmit={submit}>
      <h2>{id ? "Edit project" : "New project"}</h2>
      <div className="grid">
        <div>
          <label>Name</label>
          <input value={form.name} onChange={(e) => set("name", e.target.value)} required />
        </div>
        <div>
          <label>GitLab URL</label>
          <input value={form.gitlabUrl} onChange={(e) => set("gitlabUrl", e.target.value)} required />
        </div>
        <div>
          <label>GitLab project id or path</label>
          <input value={form.gitlabProjectId} onChange={(e) => set("gitlabProjectId", e.target.value)} required />
        </div>
        <div>
          <label>GitLab PAT {id && <span className="muted">(blank keeps current)</span>}</label>
          <input value={form.gitlabToken} onChange={(e) => set("gitlabToken", e.target.value)} />
        </div>
        <div>
          <label>Provider</label>
          <select value={form.provider} onChange={(e) => set("provider", e.target.value as Project["provider"])}>
            <option>ANTHROPIC</option>
            <option>OPENAI_COMPAT</option>
          </select>
        </div>
        <div>
          <label>Model</label>
          <input value={form.model} onChange={(e) => set("model", e.target.value)} required />
        </div>
        <div>
          <label>Model base URL</label>
          <input value={form.modelBaseUrl} onChange={(e) => set("modelBaseUrl", e.target.value)} />
        </div>
        <div>
          <label>Model key {id && <span className="muted">(blank keeps current)</span>}</label>
          <input value={form.modelKey} onChange={(e) => set("modelKey", e.target.value)} />
        </div>
      </div>
      <label>Initial review skill {id && <span className="muted">(managed on the project page)</span>}</label>
      {!id && <textarea value={form.skill} onChange={(e) => set("skill", e.target.value)}
        placeholder="Optional. Becomes the first enabled skill, named reviewer." />}
      {id && <p className="muted">Add, enable, and edit the skill set on the project page — same idea as MCP tools.</p>}
      <div className="opt-block">
        <label>Review</label>
        <div className="toggles">
          <Toggle on={form.skipDrafts} onChange={(v) => set("skipDrafts", v)}>Skip drafts</Toggle>
          <Toggle on={form.autoApprove} onChange={(v) => set("autoApprove", v)}>Auto-approve when clean</Toggle>
          {id && <Toggle on={form.enabled} onChange={(v) => set("enabled", v)}>Enabled</Toggle>}
        </div>
      </div>
      <div className="opt-block">
        <label>Agent</label>
        <p className="muted">Needs max turns &gt; 1 for tools. The MR card is always written from the verdict.</p>
        <label>Max turns</label>
        <input type="number" min={1} value={form.agentMaxTurns}
          onChange={(e) => set("agentMaxTurns", Number(e.target.value))}
          style={{ maxWidth: 120 }} />
        <div className="toggles">
          {AGENT_TOOLS.map((name) => {
            const on = form.agentTools.split(",").map((s) => s.trim()).includes(name);
            return (
              <Toggle key={name} on={on} onChange={(v) => {
                const names = new Set(form.agentTools.split(",").map((s) => s.trim()).filter(Boolean));
                if (v) names.add(name); else names.delete(name);
                set("agentTools", [...names].join(","));
              }}>{name}</Toggle>
            );
          })}
        </div>
      </div>
      <div className="opt-block">
        <label>Librarian</label>
        <div className="toggles">
          <Toggle on={form.librarianEnabled} onChange={(v) => set("librarianEnabled", v)}>Librarian</Toggle>
        </div>
        <p className="muted">Initialize / Refresh. Turning off never deletes files.</p>
      </div>
      {err && <p className="err">{err}</p>}
      <button className="primary" type="submit">{id ? "Save" : "Create"}</button>
    </form>
  );
}

function MrCycle({ projectId, iid }: { projectId: string; iid: string }) {
  const [cycle, setCycle] = useState<Cycle | null>(null);
  const [card, setCard] = useState<MemoryDocument | null>(null);
  const [latest, setLatest] = useState<Review | null>(null);
  const [err, setErr] = useState<string | null>(null);

  function load() {
    api<Cycle>(`/api/projects/${projectId}/merge-requests/${iid}`)
      .then((c) => {
        setCycle(c);
        const id = c.status.latestReviewId;
        if (id) {
          api<Review>(`/api/reviews/${id}`).then(setLatest).catch(() => setLatest(null));
        } else {
          setLatest(null);
        }
      }).catch((e) => setErr(String(e.message)));
    api<MemoryDocument>(`/api/projects/${projectId}/memory/mrs/${iid}`)
      .then(setCard).catch(() => setCard(null));
  }
  useEffect(load, [projectId, iid]);
  usePoll(load, true);

  const reviewing = cycle?.status.currentStatus === "REVIEWING"
    || (cycle?.reviews.some((r) => r.state === "PENDING" || r.state === "PROCESSING") ?? false);

  async function rerun() {
    if (!cycle?.status.latestReviewId) return;
    await api(`/api/reviews/${cycle.status.latestReviewId}/rerun`, { method: "POST" });
    load();
  }

  if (err) return <p className="err">{err}</p>;
  if (!cycle) return <p className="muted">Loading…</p>;

  const title = cycle.reviews[0]?.mr?.title;
  const snapshot = cycle.reviews[0];
  return (
    <div>
      <div className="row">
        <a href={`/projects/${projectId}`}
          onClick={(e) => { e.preventDefault(); go(`/projects/${projectId}`); }}>
          ← project
        </a>
        <h2 style={{ margin: 0 }}>!{iid} {title}</h2>
        {pill(cycle.status.currentStatus)}
        {cycle.status.mrrApproved && <span className="pill ok">mrr approved</span>}
        {snapshot?.mr?.url && <a href={snapshot.mr.url} target="_blank" rel="noreferrer">GitLab</a>}
        {cycle.status.latestReviewId && <button onClick={rerun}>Re-run latest</button>}
      </div>
      <GitMeta mr={snapshot?.mr} sha={snapshot?.headSha ?? cycle.status.latestHeadSha} />
      <p className="muted">{cycle.status.openFindings} open finding(s)</p>
      <MrContext projectId={projectId} iid={iid} initial={cycle.status.userContext} onSaved={load} />
      {cycle.reviews.some((r) => r.state === "FAILED") && (
        <FailureBanner
          kind={cycle.reviews.find((r) => r.state === "FAILED")?.errorKind}
          message={cycle.reviews.find((r) => r.state === "FAILED")?.error}
        />
      )}
      <div className="split">
        <div>
          <h2>Findings</h2>
          {cycle.findings.length === 0 && <p className="muted">None.</p>}
          {cycle.findings.map((f) => (
            <div className={`card finding ${f.state.toLowerCase()} ${f.severity.toLowerCase()}`} key={f.id}>
              <div className="row">
                {pill(f.severity)} {pill(f.state)}
                <strong>{f.title}</strong>
              </div>
              <p className="muted" style={{ margin: "6px 0 0" }}>
                {f.filePath}{f.line ? `:${f.line}` : ""}
              </p>
            </div>
          ))}
        </div>
        <div>
          <div className="row">
            <h2 style={{ margin: 0 }}>Trace</h2>
            {reviewing && <span className="log-live" title="running" />}
            {latest && (
              <a href={`/reviews/${latest.id}`} onClick={(e) => { e.preventDefault(); go(`/reviews/${latest.id}`); }}>
                full review
              </a>
            )}
          </div>
          <Trail trace={latest?.trace ?? null} empty="No trace yet." />
        </div>
      </div>
      <details className="fold">
        <summary>Memory card</summary>
        {card ? (
          <div>
            <div className="row">
              {card.status && pill(card.status)}
              <strong>{card.title}</strong>
              <a href={`/projects/${projectId}`}
                onClick={(e) => { e.preventDefault(); go(`/projects/${projectId}`); }}>
                project memory
              </a>
            </div>
            <pre>{card.body || "(empty)"}</pre>
          </div>
        ) : (
          <p className="muted">
            {reviewing ? "Waiting for the review to write this MR's card…" : "No memory card yet."}
          </p>
        )}
      </details>
      <h2>Reviews</h2>
      <table>
        <thead><tr><th>When</th><th>SHA</th><th>Turns</th><th>State</th><th>Error</th></tr></thead>
        <tbody>
          {cycle.reviews.map((r) => (
            <tr key={r.id}>
              <td>{r.createdAt}</td>
              <td>
                <a href={`/reviews/${r.id}`} onClick={(e) => { e.preventDefault(); go(`/reviews/${r.id}`); }}>
                  {r.headSha.slice(0, 8)}
                </a>
              </td>
              <td className="muted">{r.agentTurns ?? 0}</td>
              <td>{pill(r.state)}</td>
              <td className="muted">{r.errorKind ?? ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2>Replies</h2>
      {(cycle.replies ?? []).length === 0 && <p className="muted">None yet.</p>}
      {(cycle.replies ?? []).map((r) => (
        <ReplyCard key={r.id} summary={r} />
      ))}
    </div>
  );
}

function MrContext({ projectId, iid, initial, onSaved }: {
  projectId: string; iid: string; initial: string | null; onSaved: () => void;
}) {
  const [text, setText] = useState(initial ?? "");
  const [open, setOpen] = useState(!!initial);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    setText(initial ?? "");
    setOpen(!!initial);
  }, [initial]);

  async function save(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setErr(null);
    try {
      await api(`/api/projects/${projectId}/merge-requests/${iid}/context`, {
        method: "PUT", body: JSON.stringify({ context: text }),
      });
      onSaved();
    } catch (e) {
      setErr(String((e as Error).message));
    } finally {
      setSaving(false);
    }
  }

  return (
    <details className="fold" open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>Merge request context {initial ? "" : "(none)"}</summary>
      <p className="muted">
        Notes for the reviewer about this MR — ticket numbers, intent, what to ignore.
        Included on the next push or re-run; saving does not start a review.
      </p>
      <form onSubmit={save}>
        <textarea placeholder="e.g. Implements JIRA-4412. The feature flag is off in prod."
          value={text} onChange={(e) => setText(e.target.value)} />
        {err && <p className="err">{err}</p>}
        <p className="row">
          <button className="primary" type="submit" disabled={saving}>
            {saving ? "Saving…" : "Save"}
          </button>
        </p>
      </form>
    </details>
  );
}

function ReplyCard({ summary }: { summary: Reply }) {
  const [full, setFull] = useState<Reply | null>(null);
  const [open, setOpen] = useState(false);

  async function toggle() {
    if (open) {
      setOpen(false);
      return;
    }
    if (!full) {
      setFull(await api<Reply>(`/api/replies/${summary.id}`));
    }
    setOpen(true);
  }

  return (
    <div className="card">
      <div className="row">
        <span className="muted">{summary.createdAt}</span>
        <button onClick={toggle}>{open ? "Hide transcript" : "Transcript"}</button>
      </div>
      {summary.response && <p>{summary.response}</p>}
      {open && full && (
        <>
          <h2>Prompt</h2>
          <pre>{full.prompt}</pre>
          <Trail trace={full.trace} />
        </>
      )}
    </div>
  );
}

function ReviewDetail({ id }: { id: string }) {
  const [review, setReview] = useState<Review | null>(null);
  const [err, setErr] = useState<string | null>(null);

  function load() {
    api<Review>(`/api/reviews/${id}`).then(setReview).catch((e) => setErr(String(e.message)));
  }
  useEffect(load, [id]);
  const inflight = review?.state === "PENDING" || review?.state === "PROCESSING";
  usePoll(load, !!inflight);

  if (err) return <p className="err">{err}</p>;
  if (!review) return <p className="muted">Loading…</p>;

  return (
    <div>
      <div className="row">
        <a href={`/projects/${review.projectId}/mrs/${review.mrIid}`}
          onClick={(e) => { e.preventDefault(); go(`/projects/${review.projectId}/mrs/${review.mrIid}`); }}>
          ← !{review.mrIid}
        </a>
        <h2 style={{ margin: 0 }}>{review.mr?.title ?? review.id}</h2>
        {pill(review.state)}
      </div>
      <p className="muted">
        !{review.mrIid} · {review.headSha.slice(0, 8)} · {review.createdAt}
        {review.mr?.url && <> · <a href={review.mr.url} target="_blank" rel="noreferrer">GitLab</a></>}
      </p>
      <GitMeta mr={review.mr} sha={review.headSha} />
      {review.error && <FailureBanner kind={review.errorKind} message={review.error} />}
      <p className="muted">{review.agentTurns ?? 0} turn(s)</p>
      <h2>Prompt</h2>
      <pre>{review.prompt}</pre>
      <Trail trace={review.trace} />
      <h2>Response</h2>
      <pre>{review.response}</pre>
    </div>
  );
}

function Trail({ trace, empty }: { trace: AgentTrace | null; empty?: string }) {
  if (!trace || !trace.events?.length) {
    return empty ? <p className="muted">{empty}</p> : null;
  }
  return (
    <div className="rail">
      <p className="muted">{trace.turns}/{trace.maxTurns} turns</p>
      {trace.events.map((e, i) => {
        const tools = e.toolCalls?.map((c) => c.name).join(", ");
        const label = [e.role, e.name, tools, e.stopReason, e.elapsedMs != null ? `${e.elapsedMs}ms` : null]
          .filter(Boolean).join(" · ");
        const hasBody = !!(e.text || e.result || (e.toolCalls && e.toolCalls.length));
        return (
          <div className="trail-line" key={i}>
            <div className="row">
              <span className="role">turn {e.turn}</span>
              <span>{label}</span>
              {e.truncated && pill("truncated")}
            </div>
            {hasBody && (
              <details className="fold">
                <summary>details</summary>
                {e.toolCalls && e.toolCalls.length > 0 && (
                  <pre>{e.toolCalls.map((c) => `${c.name} ${c.arguments}`).join("\n")}</pre>
                )}
                {e.text && <pre>{e.text}</pre>}
                {e.result && <pre>{e.result}</pre>}
              </details>
            )}
          </div>
        );
      })}
    </div>
  );
}

function highlightLog(message: string) {
  const re = /(review [a-zA-Z0-9_-]+|turn \d+\/\d+|stop_reason=\S+|tools?=\S+|read_file|\d+ms|\d+ chars|max_turns=\d+|OPENAI_COMPAT|ANTHROPIC|"[^"]*"|'[^']*'|\b(?:failed|error|ERROR|WARN|truncated)\b)/g;
  const parts: ReactNode[] = [];
  let last = 0;
  let match: RegExpExecArray | null;
  let i = 0;
  while ((match = re.exec(message)) !== null) {
    if (match.index > last) parts.push(message.slice(last, match.index));
    const tok = match[0];
    const lower = tok.toLowerCase();
    let cls = "log-tok-key";
    if (lower.startsWith("review ") || /^[a-f0-9-]{8,}$/.test(tok)) cls = "log-tok-id";
    else if (/^\d+ms$/.test(tok) || /^\d+ chars$/.test(tok) || lower.startsWith("turn ") || lower.startsWith("max_turns=")) cls = "log-tok-num";
    else if (tok.startsWith("\"") || tok.startsWith("'")) cls = "log-tok-str";
    else if (lower.startsWith("tool") || lower === "read_file" || lower === "search_memory"
      || lower === "read_memory" || lower === "write_memory" || lower === "list_tree"
      || lower === "openai_compat" || lower === "anthropic") cls = "log-tok-tool";
    else if (lower === "failed" || lower === "error" || lower === "warn" || lower === "truncated") cls = "log-tok-bad";
    parts.push(<span key={i++} className={cls}>{tok}</span>);
    last = match.index + tok.length;
  }
  if (last < message.length) parts.push(message.slice(last));
  return parts;
}

function Logs() {
  const [loggers, setLoggers] = useState<LoggerState[]>([]);
  const [lines, setLines] = useState<LogLine[]>([]);
  const [buffered, setBuffered] = useState(0);
  const [stick, setStick] = useState(true);
  const [mrrOnly, setMrrOnly] = useState(true);
  const [filter, setFilter] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const lastSeq = useRef(0);
  const stickRef = useRef(true);

  async function loadLevels() {
    setLoggers(await api<LoggerState[]>("/api/loggers"));
  }

  const pull = useRef<(reset: boolean) => Promise<void>>(async () => {});
  pull.current = async (reset: boolean) => {
    const after = reset ? 0 : lastSeq.current;
    const snap = await api<LogSnapshot>(`/api/logs?after=${after}&limit=500`);
    setBuffered(snap.buffered);
    if (reset) {
      setLines(snap.lines);
    } else if (snap.lines.length) {
      setLines((prev) => [...prev, ...snap.lines].slice(-2000));
    }
    const newest = snap.lines.at(-1)?.seq;
    if (newest) lastSeq.current = newest;
    else if (reset) lastSeq.current = 0;
  };

  useEffect(() => {
    loadLevels().catch((e) => setErr(String(e.message)));
    pull.current(true).catch((e) => setErr(String(e.message)));
    const id = window.setInterval(() => {
      pull.current(false).catch((e) => setErr(String(e.message)));
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    if (stickRef.current && box.current) {
      box.current.scrollTop = box.current.scrollHeight;
    }
  }, [lines]);

  function onScroll() {
    const el = box.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
    stickRef.current = atBottom;
    setStick(atBottom);
  }

  async function setLevel(loggerName: string, level: string) {
    await api("/api/loggers", { method: "PUT", body: JSON.stringify({ name: loggerName, level }) });
    loadLevels();
  }

  async function toggleDebug() {
    const app = loggers.find((l) => l.name === "ro.bitboy.mrr");
    const on = ["DEBUG", "TRACE"].includes((app?.effectiveLevel ?? "").toUpperCase());
    await setLevel("ro.bitboy.mrr", on ? "INHERIT" : "DEBUG");
  }

  async function clear() {
    await api("/api/logs", { method: "DELETE" });
    lastSeq.current = 0;
    setLines([]);
    setBuffered(0);
  }

  const app = loggers.find((l) => l.name === "ro.bitboy.mrr");
  const debugOn = ["DEBUG", "TRACE"].includes((app?.effectiveLevel ?? "").toUpperCase());
  const q = filter.trim().toLowerCase();
  const shown = lines.filter((l) => {
    if (mrrOnly && !l.logger.startsWith("ro.bitboy.mrr")) return false;
    if (!q) return true;
    return `${l.level} ${l.logger} ${l.message} ${l.thrown ?? ""}`.toLowerCase().includes(q);
  });

  function fmt(at: string) {
    const d = new Date(at);
    if (Number.isNaN(d.getTime())) return at;
    return d.toISOString().slice(11, 23);
  }

  function shortLogger(name: string) {
    const i = name.lastIndexOf(".");
    return i < 0 ? name : name.slice(i + 1);
  }

  return (
    <div>
      <div className="row">
        <h2 style={{ margin: 0 }}>Logs</h2>
        <span className={stick ? "log-live" : "log-live off"} title={stick ? "Live" : "Paused — scroll to bottom"} />
        <span className="muted">{stick ? "live" : "paused"}</span>
        {pill(debugOn ? "DEBUG" : (app?.effectiveLevel ?? "INFO"))}
        <button className={debugOn ? "danger" : "primary"} onClick={toggleDebug}>
          {debugOn ? "Debug off" : "Debug on"}
        </button>
        <Toggle on={mrrOnly} onChange={setMrrOnly}>mrr only</Toggle>
        {!stick && (
          <button className="primary" onClick={() => {
            stickRef.current = true;
            setStick(true);
            if (box.current) box.current.scrollTop = box.current.scrollHeight;
          }}>Jump to latest</button>
        )}
        <button onClick={clear}>Clear</button>
        <span className="muted" style={{ marginLeft: "auto" }}>{buffered} buffered</span>
      </div>
      <p className="muted">
        Refreshes every second. Scroll up to pause auto-scroll. Debug on logs full agent bodies
        (truncated at 16k here). HTTP header and wire loggers stay at WARN so GitLab tokens
        are not dumped.
      </p>
      {loggers.map((l) => (
        <div className="row" key={l.name} style={{ margin: "4px 0" }}>
          <code>{l.name}</code>
          <span className="muted">effective {l.effectiveLevel}</span>
          <select
            value={l.configuredLevel ?? "INHERIT"}
            onChange={(e) => setLevel(l.name, e.target.value)}
            style={{ width: "auto" }}
          >
            {["INHERIT", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF"].map((lvl) => (
              <option key={lvl} value={lvl}>{lvl}</option>
            ))}
          </select>
        </div>
      ))}
      <input
        placeholder="Filter…"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        style={{ margin: "8px 0" }}
      />
      {err && <p className="err">{err}</p>}
      <div className="log-view" ref={box} onScroll={onScroll}>
        {shown.length === 0 && <p className="muted" style={{ padding: "8px 12px" }}>No lines yet. Trigger a review, or turn Debug on.</p>}
        {shown.map((l) => (
          <div key={l.seq} className={`log-line ${l.level.toLowerCase()}`}>
            <div className="log-row">
              <span className="log-time">{fmt(l.at)}</span>
              <span className="log-lvl">{l.level}</span>
              <span className="log-logger" title={l.logger}>{shortLogger(l.logger)}</span>
              <span className="log-msg">{highlightLog(l.message)}</span>
            </div>
            {l.thrown && <div className="log-thrown">{l.thrown}</div>}
          </div>
        ))}
      </div>
    </div>
  );
}

function Admin() {
  const [users, setUsers] = useState<User[]>([]);
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [name, setName] = useState("");
  const [minted, setMinted] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  function load() {
    Promise.all([api<User[]>("/api/users"), api<TokenInfo[]>("/api/tokens")])
      .then(([u, t]) => { setUsers(u); setTokens(t); })
      .catch((e) => setErr(String(e.message)));
  }
  useEffect(load, []);

  async function createUser(e: FormEvent) {
    e.preventDefault();
    await api("/api/users", { method: "POST", body: JSON.stringify({ name, role: "USER" }) });
    setName("");
    load();
  }

  async function mint(userId: string) {
    const created = await api<{ token: string }>("/api/tokens", {
      method: "POST",
      body: JSON.stringify({ userId, name: "ui" }),
    });
    setMinted(created.token);
    load();
  }

  async function revoke(id: string) {
    await api(`/api/tokens/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <div>
      <h2>Users</h2>
      {err && <p className="err">{err}</p>}
      <form className="row" onSubmit={createUser}>
        <input placeholder="name" value={name} onChange={(e) => setName(e.target.value)} />
        <button className="primary" type="submit">Create USER</button>
      </form>
      <table>
        <thead><tr><th>Name</th><th>Role</th><th></th></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td>{u.name}</td>
              <td>{u.role}</td>
              <td><button onClick={() => mint(u.id)}>Mint token</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {minted && (
        <div className="card">
          <p>Shown once:</p>
          <input readOnly value={minted} />
        </div>
      )}
      <h2>Your tokens</h2>
      <table>
        <thead><tr><th>Name</th><th>Prefix</th><th></th></tr></thead>
        <tbody>
          {tokens.map((t) => (
            <tr key={t.id}>
              <td>{t.name}</td>
              <td className="muted">{t.prefix}</td>
              <td><button className="danger" onClick={() => revoke(t.id)}>Revoke</button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
