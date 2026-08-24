"use client";

import { FormEvent, ReactNode, useEffect, useMemo, useRef, useState } from "react";

const TOKEN_KEY = "mrr.token";

type Me = { userId: string; name: string; role: "ADMIN" | "USER" };
type Provider = "ANTHROPIC" | "OPENAI_COMPAT";
// Which host a project's merge requests live on. Not to be confused with `Provider`, which is
// the LLM vendor — the API keeps that name for the model and calls this one `forge`.
type ForgeKind = "GITLAB" | "FORGEJO" | "GITHUB";
const FORGE_NAMES: Record<ForgeKind, string> = {
  GITLAB: "GitLab",
  FORGEJO: "Forgejo / Gitea / Codeberg",
  GITHUB: "GitHub",
};
// `gitlabUrl` / `gitlabToken` are the forge base URL and token whichever forge this is. The
// names are kept because they are baked into the API, the MCP tools and this file.
const REPO_ID_HINT: Record<ForgeKind, string> = {
  GITLAB: "numeric id or group/project",
  FORGEJO: "owner/repo",
  GITHUB: "owner/repo",
};
type Project = {
  id: string;
  name: string;
  enabled: boolean;
  forge: ForgeKind;
  gitlabUrl: string;
  gitlabProjectId: string;
  gitlabToken?: string | null;
  gitlabOperatorToken?: string | null;
  // The model configuration is the catalog entry's, resolved on every read. `llm` is that
  // entry with its key masked; the project stores only the reference.
  llmProviderId: string;
  llm: LlmProvider | null;
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
  approvedBy: string | null;
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
  detailedMergeStatus?: string | null;
  divergedCommitsCount?: number | null;
  hasConflicts?: boolean | null;
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
  libraryId?: string | null;
};
type LibrarySkill = {
  id: string;
  ownerId: string;
  ownerName: string | null;
  name: string;
  body: string;
  createdAt: string;
  updatedAt: string;
};
type ForgeInstance = {
  id: string; ownerId: string | null; name: string; kind: ForgeKind; url: string; createdAt: string;
};
type ForgeReport = {
  forge: ForgeKind;
  forgeName: string;
  baseUrl: string;
  requirements: { name: string; title: string; status: "OK" | "MISSING" | "UNKNOWN"; detail: string }[];
};
type LlmProvider = {
  id: string;
  ownerId: string | null;   // null = shared entry, curated by an admin, never carries a key
  name: string;
  provider: Provider;
  model: string;
  modelBaseUrl: string | null;
  modelKey: string | null;  // masked on read; a shared entry has none
  createdAt: string;
};
type InstanceInfo = { baseUrl: string; webhookUrlTemplate: string };
type SettingsView = {
  live: Record<string, string | number>;
  sources: Record<string, "env" | "saved">;
  restartOnly: Record<string, string>;
};
type InstanceStats = {
  mrsProcessed: number;
  reviewsTotal: number;
  reviewsByState: Record<string, number>;
  inFlight: number;
  pendingOrProcessing: number;
  tokens: { input: number; output: number; cacheRead: number };
  tokensLast7Days: { input: number; output: number; cacheRead: number };
  findingsOpen: number;
  findingsAddressed: number;
  findingsApproved: number;
  meanReadyDurationSeconds: number | null;
  failuresByKind: Record<string, number>;
  projects: {
    projectId: string;
    name: string;
    mrs: number;
    reviews: number;
    inputTokens: number;
    outputTokens: number;
  }[];
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
  | { name: "skills" }
  | { name: "stats" }
  | { name: "projects" }
  | { name: "project"; id: string }
  | { name: "edit"; id: string }
  | { name: "new" }
  | { name: "mr"; id: string; iid: string }
  | { name: "review"; id: string }
  | { name: "admin" }
  | { name: "logs" }
  | { name: "configure" };

function parseRoute(path: string): Route {
  if (path === "/" || path === "/login" || path === "/howto") return { name: "howto" };
  if (path === "/projects") return { name: "projects" };
  if (path === "/projects/new") return { name: "new" };
  if (path === "/admin") return { name: "admin" };
  if (path === "/logs") return { name: "logs" };
  if (path === "/configure") return { name: "configure" };
  if (path === "/skills") return { name: "skills" };
  if (path === "/stats") return { name: "stats" };
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
  const cls = s === "CLEAN" || s === "READY" || s === "ADDRESSED" || s === "APPROVED" || s === "ENABLED" || s === "ON" || s === "MERGED" ? "ok"
    : s === "NEEDS_WORK" || s === "OPEN" || s === "REVIEWING" || s === "PENDING"
      || s === "PROCESSING" || s === "RUNNING" || s === "INITIALIZE" || s === "REFRESH" ? "warn"
    : s === "FAILED" || s === "CANCELLED" || s === "BLOCKER" || s === "QUOTA" || s === "PROVIDER"
      || s === "OUTPUT" || s === "AUTH" || s === "MCP" || s === "GITLAB" || s === "RATE_LIMIT"
      || s === "REFUSAL" ? "bad"
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

  const target = mr.targetBranch || "target";
  const behind = (mr.divergedCommitsCount ?? 0) > 0 || mr.detailedMergeStatus === "need_rebase";
  const conflicts = mr.hasConflicts === true || mr.detailedMergeStatus === "conflict";
  const hasGit = !!(mr.sourceBranch || mr.commitCount != null || mr.filesChanged != null
    || sha || behind || conflicts);
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
      {conflicts && <span className="pill bad">conflicts with {target}</span>}
      {behind && (
        <span className="pill warn">
          {(mr.divergedCommitsCount ?? 0) > 0
            ? `${mr.divergedCommitsCount} ${mr.divergedCommitsCount === 1 ? "commit" : "commits"} behind ${target}`
            : `needs rebase onto ${target}`}
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
                <a href="/configure" onClick={(e) => { e.preventDefault(); go("/configure"); }}>Configure</a>
                <a href="/skills" onClick={(e) => { e.preventDefault(); go("/skills"); }}>Skills</a>
                <a href="/stats" onClick={(e) => { e.preventDefault(); go("/stats"); }}>Stats</a>
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
        {route.name === "configure" && me.role === "ADMIN" && <Configure />}
        {route.name === "skills" && me.role === "ADMIN" && <SkillLibrary />}
        {route.name === "stats" && me.role === "ADMIN" && <Stats />}
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
      <h1>Hello, I’m mrr 😼</h1>
      <p className="lead">
        I read your merge requests and comment on them just like your colleagues would —
        faster, and I never forget to look.
      </p>
      <p>Setup takes about five minutes.</p>

      <h2>1. Log in</h2>
      <pre className="token-box">{`┌─ mrr  root admin token ────────────────────────────────────┐
│  mrr_…                                                     │
└────────────────────────────────────────────────────────────┘`}</pre>
      <p>
        First boot prints a root token — it’s also saved at <code>/data/root-token.txt</code>.
        Paste it on the login screen. There’s no password.
      </p>
      {onLogin && <TokenForm onLogin={onLogin} />}
      <p>
        Later, from Admin, you can issue one user token per person so nobody has to share root.
      </p>

      <h2>2. Add your project</h2>
      <p><strong>Projects → New Project.</strong> You’ll need:</p>
      <ul>
        <li>Your GitLab URL</li>
        <li>The project ID, or <code>group/project</code></li>
        <li>
          A GitLab token — a <strong>project access token</strong> is the easy answer:
          bot user, Developer role, <code>api</code> scope
        </li>
        <li>
          Your model provider — Anthropic, or OpenAI-compatible with a base URL for
          Ollama / vLLM
        </li>
      </ul>
      <p>That’s it. mrr scans your open merge requests and gets to work.</p>
      <p className="muted">
        For the first review, try max turns 1 with tools off — quickest way to confirm
        everything’s wired up.
      </p>

      <h2>3. Add a webhook (recommended, not required)</h2>
      <p>
        mrr checks for new commits on its own, so reviews will show up regardless. The webhook
        just makes them instant.
      </p>
      <p>
        After you create the project, copy the URL and secret — shown once — into{" "}
        <strong>GitLab → Settings → Webhooks</strong>, and tick:
      </p>
      <ul>
        <li><strong>Merge request events</strong></li>
        <li><strong>Comments</strong> — only if you want mrr to answer replies on its own threads</li>
      </ul>
      <p>
        <code>MRR_BASE_URL</code> has to be reachable from GitLab. If it ever changes, edit the
        existing hook rather than adding a second one — two hooks means every review runs twice.
      </p>

      <h2>What a review looks like</h2>
      <ul>
        <li><strong>One overview comment</strong> (<code>### mrr review</code>), edited in place rather than reposted</li>
        <li><strong>One thread per finding</strong></li>
        <li><strong>On new commits:</strong> fixed findings resolve themselves, open ones stay put, new problems get new threads</li>
        <li>
          <strong>Resolve a thread in GitLab</strong> and mrr marks the finding approved against
          your username — the two stay in sync either way
        </li>
      </ul>
      <p>
        One thing that surprises people: editing a title or label without pushing won’t trigger
        a new review (or spend tokens). Drafts get reviewed unless you turn on{" "}
        <strong>Skip drafts</strong>.
      </p>

      <h2>Which GitLab token?</h2>
      <table>
        <thead><tr><th>Token</th><th>Notes</th></tr></thead>
        <tbody>
          <tr>
            <td>Project or group access token</td>
            <td>
              Preferred. Bot user, Developer + <code>api</code>. On gitlab.com needs
              Premium/Ultimate; self-hosted Free is fine.
            </td>
          </tr>
          <tr>
            <td>Fine-grained PAT</td>
            <td>Scoped to a single project. Permissions below.</td>
          </tr>
          <tr>
            <td>Classic PAT</td>
            <td><code>api</code> scope, and the user needs Developer on the project.</td>
          </tr>
        </tbody>
      </table>
      <p className="muted">
        Deploy tokens and CI job tokens won’t work — mrr sends the token as{" "}
        <code>PRIVATE-TOKEN</code>.
      </p>

      <h3>Fine-grained permissions</h3>
      <p>
        Always: <code>Merge Request: Read</code>, <code>Create</code>, <code>Update</code>.
      </p>
      <p>Only if you turn the matching feature on:</p>
      <ul>
        <li>
          <code>Merge Request: Approve</code> — for auto-approve. The token’s identity also has
          to be allowed to approve under your approval rules and CODEOWNERS.
        </li>
        <li>
          <code>Repository: Read</code>, <code>Commit: Read</code>, <code>Project: Read</code> —
          for <code>read_file</code> and the librarian.
        </li>
      </ul>
      <p>
        Please don’t grant Merge, repo write, <code>Code: Push</code>, <code>Code: Read</code>,
        or webhook scopes. mrr never pushes, never merges, and never creates the hook itself.
      </p>

      <h2>Optional extras</h2>
      <ul>
        <li><strong>Skills / documents</strong> — add them in the Reviewer tab; they apply from the next review on.</li>
        <li>
          <strong>Memory</strong> — a card is written when a review reaches READY. The reviewer
          only reads it if it calls <code>search_memory</code> or <code>read_memory</code>.
        </li>
        <li>
          <strong>Librarian</strong> (off by default) — Initialize and Refresh only touch{" "}
          <code>project/</code>. Disabling it never deletes files.
        </li>
        <li><strong>Auto-approve</strong> — needs <code>Merge Request: Approve</code>.</li>
      </ul>

      <h2>Nothing showed up on GitLab</h2>
      <p>
        If a review FAILED — quota, rate limit, unreachable provider, model refusal — mrr posts
        nothing at all rather than posting something partial. Just re-run it. A restart also
        leaves in-flight reviews stuck in PROCESSING; re-run those too.
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
            <span className="muted" style={{ marginLeft: "auto" }}>{p.llm?.model ?? "—"}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * What this project's host can and cannot do, checked when the page loads.
 *
 * <p>The point is that a limitation is known before the first review rather than discovered as
 * a 403 an afternoon later — so this stays quiet when everything is supported and speaks up,
 * with what mrr does instead, when something is not.
 */
function ForgeSupport({ projectId }: { projectId: string }) {
  const [report, setReport] = useState<ForgeReport | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let live = true;
    api<ForgeReport>(`/api/projects/${projectId}/forge`)
      .then((r) => { if (live) setReport(r); })
      .catch(() => { /* a probe that will not answer is not worth an error banner */ });
    return () => { live = false; };
  }, [projectId]);

  if (!report) return null;
  const gaps = report.requirements.filter((r) => r.status !== "OK");
  if (gaps.length === 0) return null;

  // A confirmed gap and an inconclusive probe are different claims — conflating them under
  // one "does not support" would read as a certainty during a Forgejo outage or with its API
  // docs disabled, when mrr genuinely could not tell either way.
  const missing = gaps.filter((r) => r.status === "MISSING").length;
  const unknown = gaps.length - missing;
  const headline = missing === 0
    ? `${report.forgeName} — mrr could not verify ${unknown} thing${unknown === 1 ? "" : "s"} its process uses`
    : unknown === 0
    ? `${report.forgeName} does not support ${missing} thing${missing === 1 ? "" : "s"} mrr's process uses`
    : `${report.forgeName} does not support ${missing} thing${missing === 1 ? "" : "s"} mrr's process uses, `
      + `and could not verify ${unknown} more`;

  return (
    <div className="callout">
      <button className="link" onClick={() => setOpen(!open)}>
        {open ? "▾" : "▸"} {headline}
      </button>
      {open && (
        <table>
          <thead><tr><th>Capability</th><th>Status</th><th>What mrr does</th></tr></thead>
          <tbody>
            {gaps.map((r) => (
              <tr key={r.name}>
                <td>{r.title}</td>
                <td><span className="pill">{r.status.toLowerCase()}</span></td>
                <td className="muted">{r.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
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
        {FORGE_NAMES[project.forge]} · {project.gitlabUrl}/{project.gitlabProjectId} · {project.llm?.model ?? "no entry"}
        {project.autoApprove ? " · auto-approve on" : ""}
        {project.librarianEnabled ? " · librarian on" : ""}
        {project.memoryStatus && project.memoryStatus !== "NONE" ? ` · memory ${project.memoryStatus}` : ""}
        {project.memoryHeadSha ? ` · ${project.memoryHeadSha.slice(0, 8)}` : ""}
        {" · "}turns {project.agentMaxTurns}
        {project.agentTools.length ? ` · tools ${project.agentTools.join(",")}` : ""}
      </p>
      <FailureBanner kind={project.lastErrorKind} message={project.lastError} />
      <ForgeSupport projectId={id} />
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
  const [library, setLibrary] = useState<LibrarySkill[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [draftSkill, setDraftSkill] = useState({ name: "", body: "", branchPattern: "" });
  const [pickLibraryId, setPickLibraryId] = useState("");
  const [draftDoc, setDraftDoc] = useState({ name: "", body: "" });
  const [editing, setEditing] = useState<{ type: "skill" | "doc"; id: string; body: string; branchPattern?: string } | null>(null);

  function load() {
    Promise.all([
      api<AgentSkill[]>(`/api/projects/${projectId}/skills`),
      api<AgentDocument[]>(`/api/projects/${projectId}/documents`),
      api<LibrarySkill[]>("/api/skills"),
    ]).then(([s, d, lib]) => { setSkills(s); setDocs(d); setLibrary(lib); })
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
  async function attachLibrary(e: FormEvent) {
    e.preventDefault();
    if (!pickLibraryId) return;
    await api(`/api/projects/${projectId}/skills`, {
      method: "POST", body: JSON.stringify({ libraryId: pickLibraryId, enabled: true }),
    });
    setPickLibraryId("");
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
            <label>New skill</label>
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
          <form onSubmit={attachLibrary}>
            <label>From library</label>
            <div className="grid">
              <select value={pickLibraryId} onChange={(e) => setPickLibraryId(e.target.value)}>
                <option value="">Select a library skill…</option>
                {library.filter((s) => !skills.some((p) => p.libraryId === s.id || p.name === s.name)).map((s) => (
                  <option key={s.id} value={s.id}>{s.name}{s.ownerName ? ` · ${s.ownerName}` : ""}</option>
                ))}
              </select>
              <button className="primary" type="submit" disabled={!pickLibraryId}>Attach</button>
            </div>
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
    name: "", forge: "GITLAB" as ForgeKind,
    gitlabUrl: "https://gitlab.com", gitlabProjectId: "", gitlabToken: "",
    gitlabOperatorToken: "", skill: "",
    skipDrafts: false, agentMaxTurns: 1, agentTools: "", autoApprove: false,
    librarianEnabled: false, enabled: true,
  });
  const [secret, setSecret] = useState<{ webhookUrl: string; webhookSecret: string } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [gitlabInstances, setGitlabInstances] = useState<ForgeInstance[]>([]);
  const [llmProviders, setLlmProviders] = useState<LlmProvider[]>([]);
  const [instance, setInstance] = useState<InstanceInfo | null>(null);
  const [gitlabPick, setGitlabPick] = useState("custom");
  const [llmPick, setLlmPick] = useState("");

  useEffect(() => {
    Promise.all([
      api<ForgeInstance[]>("/api/forge-instances"),
      api<LlmProvider[]>("/api/llm-providers"),
      api<InstanceInfo>("/api/instance"),
    ]).then(([hosts, providers, info]) => {
      setGitlabInstances(hosts);
      setLlmProviders(providers);
      setInstance(info);
      if (id) return;
      if (hosts.length > 0) {
        setGitlabPick(hosts[0].id);
        setForm((f) => ({ ...f, forge: hosts[0].kind, gitlabUrl: hosts[0].url }));
      }
      if (providers.length > 0) {
        const p = providers[0];
        setLlmPick(p.id);
        setForm((f) => ({
          ...f,
          provider: p.provider,
          model: p.model,
          modelBaseUrl: p.modelBaseUrl ?? "",
        }));
      }
    }).catch((e) => setErr(String((e as Error).message)));
  }, [id]);

  useEffect(() => {
    if (!id) return;
    api<Project>(`/api/projects/${id}`).then((p) => setForm((f) => ({
      ...f,
      name: p.name,
      forge: p.forge,
      gitlabUrl: p.gitlabUrl,
      gitlabProjectId: p.gitlabProjectId,
      skill: p.skill ?? "",
      skipDrafts: p.skipDrafts,
      agentMaxTurns: p.agentMaxTurns,
      agentTools: p.agentTools.join(","),
      autoApprove: p.autoApprove,
      librarianEnabled: p.librarianEnabled,
      enabled: p.enabled,
    }))).catch((e) => setErr(String(e.message)));
  }, [id]);

  // Editing an existing project starts on the entry it already references.
  useEffect(() => {
    if (!id) return;
    api<Project>(`/api/projects/${id}`)
      .then((p) => setLlmPick(p.llmProviderId))
      .catch(() => { /* the form's own load reports the error */ });
  }, [id]);

  function set<K extends keyof typeof form>(key: K, value: (typeof form)[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  // A catalog host carries both the URL and the kind, so picking one sets both — a project
  // pointed at a Codeberg entry is a Forgejo project whether or not anyone touched the select.
  function pickGitlab(value: string) {
    setGitlabPick(value);
    if (value === "custom") return;
    const host = gitlabInstances.find((g) => g.id === value);
    if (host) setForm((f) => ({ ...f, forge: host.kind, gitlabUrl: host.url }));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    const tools = form.agentTools.split(",").map((s) => s.trim()).filter(Boolean);
    const body: Record<string, unknown> = {
      name: form.name,
      gitlabProjectId: form.gitlabProjectId,
      skill: form.skill || null,
      skipDrafts: form.skipDrafts,
      agentMaxTurns: Number(form.agentMaxTurns) || 1,
      agentTools: tools,
      autoApprove: form.autoApprove,
      librarianEnabled: form.librarianEnabled,
      enabled: form.enabled,
    };
    if (gitlabPick !== "custom") body.forgeInstanceId = gitlabPick;
    else {
      body.gitlabUrl = form.gitlabUrl;
      body.forge = form.forge;
    }
    body.llmProviderId = llmPick;
    if (form.gitlabToken) body.gitlabToken = form.gitlabToken;
    if (form.gitlabOperatorToken) body.gitlabOperatorToken = form.gitlabOperatorToken;
    try {
      if (id) {
        await api(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) });
        go(`/projects/${id}`);
      } else {
        if (!form.gitlabToken) {
          setErr(`A ${FORGE_NAMES[form.forge]} token is required.`);
          return;
        }
        if (!llmPick) {
          setErr("Pick an LLM entry. Add one under Configure — the model key lives there.");
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
        <h2>Paste these into {FORGE_NAMES[form.forge]}</h2>
        <p className="muted">
          The webhook secret is shown once. Tick merge/pull request events, and comments if you
          want replies. GitLab sends the secret back as a token; Forgejo and GitHub sign the
          body with it.
        </p>
        <label>Webhook URL</label>
        <input readOnly value={secret.webhookUrl} />
        <label>Secret token</label>
        <input readOnly value={secret.webhookSecret} />
        <p><button className="primary" onClick={() => go("/projects")}>Done</button></p>
      </div>
    );
  }

  const webhookPreview = instance
    ? instance.webhookUrlTemplate
        .replace("{forge}", form.forge.toLowerCase())
        .replace("{projectId}", "<project-id>")
    : null;
  const gitlabLocked = gitlabPick !== "custom";

  return (
    <form onSubmit={submit}>
      <h2>{id ? "Edit project" : "New project"}</h2>
      {!id && webhookPreview && (
        <p className="callout">
          Webhook URL will be <code className="mono">{webhookPreview}</code>
        </p>
      )}
      <div className="grid">
        <div>
          <label>Name</label>
          <input value={form.name} onChange={(e) => set("name", e.target.value)} required />
        </div>
        <div>
          <label>Host</label>
          <select value={gitlabPick} onChange={(e) => pickGitlab(e.target.value)}>
            <option value="custom">Custom URL</option>
            {gitlabInstances.map((g) => (
              <option key={g.id} value={g.id}>{g.name} — {g.url}</option>
            ))}
          </select>
        </div>
        <div>
          <label>Forge {id && <span className="muted">(fixed after registration)</span>}</label>
          <select value={form.forge} disabled={gitlabLocked || !!id}
            onChange={(e) => set("forge", e.target.value as ForgeKind)}>
            {(Object.keys(FORGE_NAMES) as ForgeKind[]).map((k) => (
              <option key={k} value={k}>{FORGE_NAMES[k]}</option>
            ))}
          </select>
        </div>
        <div>
          <label>Base URL</label>
          <input value={form.gitlabUrl} readOnly={gitlabLocked} required={!gitlabLocked}
            onChange={(e) => { setGitlabPick("custom"); set("gitlabUrl", e.target.value); }} />
        </div>
        <div>
          <label>Repository</label>
          <input value={form.gitlabProjectId} placeholder={REPO_ID_HINT[form.forge]}
            onChange={(e) => set("gitlabProjectId", e.target.value)} required />
        </div>
        <div>
          <label>Forge PAT {id && <span className="muted">(blank keeps current)</span>}</label>
          <input value={form.gitlabToken} onChange={(e) => set("gitlabToken", e.target.value)} />
        </div>
        <div>
          <label>Operator PAT {id && <span className="muted">(blank keeps current)</span>}</label>
          <input value={form.gitlabOperatorToken} onChange={(e) => set("gitlabOperatorToken", e.target.value)}
            placeholder="Merge + overall thread from mrr" />
        </div>
        <div>
          <label>LLM</label>
          <select value={llmPick} onChange={(e) => setLlmPick(e.target.value)} required>
            <option value="">Pick an entry…</option>
            {llmProviders.map((p) => (
              <option key={p.id} value={p.id} disabled={!p.modelKey}>
                {p.name} — {p.model}{p.modelKey ? "" : " (shared, no key)"}
              </option>
            ))}
          </select>
          <p className="muted">
            The model, its base URL and its API key all come from this entry, and the project
            follows it — change the key under <a href="/configure">Configure</a> and every
            project using it moves with it. Shared entries carry no key, so they cannot be
            picked; add your own.
          </p>
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
  const [project, setProject] = useState<Project | null>(null);
  const [card, setCard] = useState<MemoryDocument | null>(null);
  const [latest, setLatest] = useState<Review | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [merging, setMerging] = useState(false);

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
    api<Project>(`/api/projects/${projectId}`).then(setProject).catch(() => setProject(null));
    api<MemoryDocument>(`/api/projects/${projectId}/memory/mrs/${iid}`)
      .then(setCard).catch(() => setCard(null));
  }
  useEffect(load, [projectId, iid]);
  usePoll(load, true);

  const reviewing = cycle?.status.currentStatus === "REVIEWING"
    || (cycle?.reviews.some((r) => r.state === "PENDING" || r.state === "PROCESSING") ?? false);
  const closed = cycle?.status.currentStatus === "MERGED" || cycle?.status.currentStatus === "CLOSED";
  const canOperate = !!project?.gitlabOperatorToken;

  async function rerun() {
    if (!cycle?.status.latestReviewId) return;
    await api(`/api/reviews/${cycle.status.latestReviewId}/rerun`, { method: "POST" });
    load();
  }

  async function merge() {
    if (!canOperate || closed) return;
    if (!window.confirm(`Merge !${iid} in GitLab as the operator PAT?`)) return;
    setMerging(true);
    setActionErr(null);
    try {
      await api(`/api/projects/${projectId}/merge-requests/${iid}/merge`, { method: "POST" });
      load();
    } catch (e) {
      setActionErr(e instanceof Error ? e.message : String(e));
    } finally {
      setMerging(false);
    }
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
        {canOperate && !closed && (
          <button className="danger" onClick={merge} disabled={merging}>
            {merging ? "Merging…" : "Merge"}
          </button>
        )}
      </div>
      <GitMeta mr={snapshot?.mr} sha={snapshot?.headSha ?? cycle.status.latestHeadSha} />
      <p className="muted">{cycle.status.openFindings} open finding(s)</p>
      {actionErr && <p className="err">{actionErr}</p>}
      {canOperate && !closed && (
        <MrThread projectId={projectId} iid={iid} onPosted={load} />
      )}
      {!canOperate && (
        <p className="muted">
          Set an operator PAT on the project to merge or post an overall thread from here.
        </p>
      )}
      <CommitDiffs projectId={projectId} iid={iid} />
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
                {f.approvedBy && ` — approved by @${f.approvedBy}`}
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

function MrThread({ projectId, iid, onPosted }: {
  projectId: string; iid: string; onPosted: () => void;
}) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function post(e: FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    setBusy(true);
    setErr(null);
    try {
      await api(`/api/projects/${projectId}/merge-requests/${iid}/discussions`, {
        method: "POST", body: JSON.stringify({ body: text.trim() }),
      });
      setText("");
      onPosted();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <details className="fold">
      <summary>Overall thread</summary>
      <p className="muted">
        Posts a new GitLab discussion as the operator PAT — not a finding reply, and not the
        review bot.
      </p>
      <form onSubmit={post}>
        <textarea placeholder="Comment on the MR as a whole…"
          value={text} onChange={(e) => setText(e.target.value)} />
        {err && <p className="err">{err}</p>}
        <p className="row">
          <button className="primary" type="submit" disabled={busy || !text.trim()}>
            {busy ? "Posting…" : "Post thread"}
          </button>
        </p>
      </form>
    </details>
  );
}

type MrCommit = {
  sha: string;
  title: string;
  authorName: string | null;
  authoredDate: string | null;
  message: string;
};
type FilePatch = { path: string; diff: string };

function CommitDiffs({ projectId, iid }: { projectId: string; iid: string }) {
  const [commits, setCommits] = useState<MrCommit[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [openSha, setOpenSha] = useState<string | null>(null);

  async function load() {
    setErr(null);
    try {
      setCommits(await api<MrCommit[]>(`/api/projects/${projectId}/merge-requests/${iid}/commits`));
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <details className="fold" onToggle={(e) => {
      if (e.currentTarget.open && commits === null && !err) load();
    }}>
      <summary>Commit diffs</summary>
      <p className="muted">
        View only. A patch is fetched when you unveil that one file — never before. No
        comments from here.
      </p>
      {err && <p className="err">{err}</p>}
      {commits === null && !err && <p className="muted">Open to load commits from GitLab…</p>}
      {commits && commits.length === 0 && <p className="muted">No commits.</p>}
      {commits?.map((c) => (
        <div className="card" key={c.sha}>
          <div className="row">
            <code>{c.sha.slice(0, 8)}</code>
            <strong>{c.title}</strong>
            {c.authorName && <span className="muted">{c.authorName}</span>}
            <button onClick={() => setOpenSha(openSha === c.sha ? null : c.sha)}>
              {openSha === c.sha ? "Hide files" : "Show files"}
            </button>
          </div>
          {openSha === c.sha && (
            <CommitFiles projectId={projectId} iid={iid} sha={c.sha} />
          )}
        </div>
      ))}
    </details>
  );
}

function CommitFiles({ projectId, iid, sha }: { projectId: string; iid: string; sha: string }) {
  const [files, setFiles] = useState<string[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());

  useEffect(() => {
    setFiles(null);
    setErr(null);
    setOpen(new Set());
    api<string[]>(`/api/projects/${projectId}/merge-requests/${iid}/commits/${sha}/files`)
      .then(setFiles)
      .catch((e) => setErr(String((e as Error).message)));
  }, [projectId, iid, sha]);

  if (err) return <p className="err">{err}</p>;
  if (!files) return <p className="muted">Loading files…</p>;
  if (files.length === 0) return <p className="muted">No file changes in this commit.</p>;

  function toggle(path: string) {
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  }

  return (
    <div className="commit-files">
      {files.map((path) => (
        <div key={path} className="diff-file">
          <button onClick={() => toggle(path)}>
            {open.has(path) ? "Hide" : "Show"} {path}
          </button>
          {open.has(path) && (
            <FilePatchView projectId={projectId} iid={iid} sha={sha} path={path} />
          )}
        </div>
      ))}
    </div>
  );
}

/** One file's patch, requested by this file's unveil and by nothing else. */
function FilePatchView({ projectId, iid, sha, path }: {
  projectId: string; iid: string; sha: string; path: string;
}) {
  const [patch, setPatch] = useState<FilePatch | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    setPatch(null);
    setErr(null);
    const url = `/api/projects/${projectId}/merge-requests/${iid}/commits/${sha}/diff`
      + `?path=${encodeURIComponent(path)}`;
    api<FilePatch>(url)
      .then((p) => { if (live) setPatch(p); })
      .catch((e) => { if (live) setErr(String((e as Error).message)); });
    return () => { live = false; };
  }, [projectId, iid, sha, path]);

  if (err) return <p className="err">{err}</p>;
  if (!patch) return <p className="muted">Loading patch…</p>;
  return <DiffView text={patch.diff} />;
}

function DiffView({ text }: { text: string }) {
  const lines = (text || "").split("\n");
  return (
    <pre className="diff-view">
      {lines.map((line, i) => {
        let cls = "";
        if (line.startsWith("+") && !line.startsWith("+++")) cls = "add";
        else if (line.startsWith("-") && !line.startsWith("---")) cls = "del";
        else if (line.startsWith("@@")) cls = "hunk";
        return <span key={i} className={cls}>{line}{"\n"}</span>;
      })}
    </pre>
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

function Configure() {
  const [settings, setSettings] = useState<SettingsView | null>(null);
  const [gitlabInstances, setGitlabInstances] = useState<ForgeInstance[]>([]);
  const [llmProviders, setLlmProviders] = useState<LlmProvider[]>([]);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [gitlabDraft, setGitlabDraft] = useState({
    name: "", kind: "GITLAB" as ForgeKind, url: "https://gitlab.com",
  });
  const [llmDraft, setLlmDraft] = useState({
    name: "", provider: "ANTHROPIC" as Provider, model: "", modelBaseUrl: "",
    modelKey: "", shared: false,
  });
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function load() {
    Promise.all([
      api<SettingsView>("/api/settings"),
      api<ForgeInstance[]>("/api/forge-instances"),
      api<LlmProvider[]>("/api/llm-providers"),
    ]).then(([s, hosts, providers]) => {
      setSettings(s);
      setGitlabInstances(hosts);
      setLlmProviders(providers);
      const next: Record<string, string> = {};
      for (const [k, v] of Object.entries(s.live)) next[k] = String(v ?? "");
      setDraft(next);
    }).catch((e) => setErr(String((e as Error).message)));
  }
  useEffect(load, []);

  function applyView(next: SettingsView) {
    setSettings(next);
    const mapped: Record<string, string> = {};
    for (const [k, v] of Object.entries(next.live)) mapped[k] = String(v ?? "");
    setDraft(mapped);
  }

  async function saveBase(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      applyView(await api<SettingsView>("/api/settings", {
        method: "PUT", body: JSON.stringify({ baseUrl: draft.baseUrl }),
      }));
    } catch (e) {
      setErr(String((e as Error).message));
    } finally {
      setBusy(false);
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      applyView(await api<SettingsView>("/api/settings", {
        method: "PUT",
        body: JSON.stringify({
          scanIntervalMs: Number(draft.scanIntervalMs),
          workers: Number(draft.workers),
          maxDiffChars: Number(draft.maxDiffChars),
          maxDiffChunks: Number(draft.maxDiffChunks),
          timeoutSeconds: Number(draft.timeoutSeconds),
          maxFileChars: Number(draft.maxFileChars),
          maxDocumentChars: Number(draft.maxDocumentChars),
          maxTraceChars: Number(draft.maxTraceChars),
          librarianMaxTurns: Number(draft.librarianMaxTurns),
          maxMemoryChars: Number(draft.maxMemoryChars),
          maxModelTokens: Number(draft.maxModelTokens),
          anthropicEffort: draft.anthropicEffort,
        }),
      }));
    } catch (e) {
      setErr(String((e as Error).message));
    } finally {
      setBusy(false);
    }
  }

  async function reset() {
    setBusy(true);
    try {
      applyView(await api<SettingsView>("/api/settings/reset", { method: "POST" }));
    } catch (e) {
      setErr(String((e as Error).message));
    } finally {
      setBusy(false);
    }
  }




  async function addGitlab(e: FormEvent) {
    e.preventDefault();
    await api("/api/forge-instances", { method: "POST", body: JSON.stringify(gitlabDraft) });
    setGitlabDraft({ name: "", kind: "GITLAB", url: "https://gitlab.com" });
    load();
  }

  async function saveGitlab(row: ForgeInstance,
                            patch: { name?: string; kind?: ForgeKind; url?: string }) {
    await api(`/api/forge-instances/${row.id}`, { method: "PUT", body: JSON.stringify(patch) });
    load();
  }

  async function removeGitlab(id: string) {
    await api(`/api/forge-instances/${id}`, { method: "DELETE" });
    load();
  }

  async function addLlm(e: FormEvent) {
    e.preventDefault();
    await api("/api/llm-providers", {
      method: "POST",
      body: JSON.stringify({
        name: llmDraft.name,
        provider: llmDraft.provider,
        model: llmDraft.model,
        modelBaseUrl: llmDraft.modelBaseUrl || null,
        modelKey: llmDraft.modelKey || null,
        shared: llmDraft.shared,
      }),
    });
    setLlmDraft({ name: "", provider: "ANTHROPIC", model: "", modelBaseUrl: "",
      modelKey: "", shared: false });
    load();
  }

  async function saveLlm(row: LlmProvider, patch: Partial<LlmProvider>) {
    await api(`/api/llm-providers/${row.id}`, { method: "PUT", body: JSON.stringify(patch) });
    load();
  }

  async function removeLlm(id: string) {
    await api(`/api/llm-providers/${id}`, { method: "DELETE" });
    load();
  }

  const fields: { key: string; label: string; type?: string; help: string }[] = [
    {
      key: "scanIntervalMs", label: "Scan interval (ms)", type: "number",
      help: "How often mrr lists GitLab’s open merge requests. Catches missed webhooks and marks merged or closed MRs. Applies on the next sleep.",
    },
    {
      key: "workers", label: "Review workers", type: "number",
      help: "How many reviews run at once. SQLite takes a write lock per connection, so keep this modest. Applies without a restart.",
    },
    {
      key: "maxDiffChars", label: "Max diff chars", type: "number",
      help: "Per-turn diff window. Larger merge requests are packed into slices instead of being silently chopped.",
    },
    {
      key: "maxDiffChunks", label: "Max diff chunks", type: "number",
      help: "Cap on how many slices one merge request can take. A merge turn then produces one verdict.",
    },
    {
      key: "timeoutSeconds", label: "HTTP timeout (seconds)", type: "number",
      help: "Timeout per GitLab or model HTTP request. A sliced review can take longer overall than this.",
    },
    {
      key: "maxFileChars", label: "Max file chars", type: "number",
      help: "Files fetched with read_file are truncated at this many characters. The reviewer still sees the start of the file.",
    },
    {
      key: "maxDocumentChars", label: "Max document chars", type: "number",
      help: "Each enabled project document stuffed into the prompt is truncated here.",
    },
    {
      key: "maxTraceChars", label: "Max trace chars", type: "number",
      help: "Tool results stored on reviews.trace / replies.trace. The model still sees the full file; DEBUG logs still print the full body.",
    },
    {
      key: "librarianMaxTurns", label: "Librarian max turns", type: "number",
      help: "Turn budget for Initialize / Refresh. Independent of the reviewer’s max turns.",
    },
    {
      key: "maxMemoryChars", label: "Max memory chars", type: "number",
      help: "Prefetched memory and file excerpts handed to the librarian on the first turn.",
    },
    {
      key: "maxModelTokens", label: "Max model tokens", type: "number",
      help: "Output budget sent as max_tokens (Anthropic and OpenAI). Covers thinking / reasoning tokens.",
    },
    {
      key: "anthropicEffort", label: "Anthropic effort",
      help: "Anthropic effort parameter. high is slower and more thorough; low is cheaper.",
    },
  ];

  if (!settings) return <p className="muted">Loading configure…</p>;

  const restartOnly: { key: string; label: string; help: string }[] = [
    { key: "serverPort", label: "SERVER_PORT", help: "HTTP port this process listens on." },
    { key: "dbPath", label: "MRR_DB_PATH / MRR_DB_URL", help: "Where users, projects, and review history live: the SQLite file by default, or the Postgres URL when MRR_DB_URL is set." },
    { key: "rootTokenFile", label: "MRR_ROOT_TOKEN_FILE", help: "Where the bootstrap admin token is written on first boot." },
    { key: "memoryRoot", label: "MRR_MEMORY_ROOT", help: "On-disk OKF files per project (project/ and mrs/)." },
    { key: "mcpEndpoint", label: "MCP endpoint", help: "Streamable HTTP path for the MCP server." },
    { key: "mcpKeepAlive", label: "MCP keep-alive", help: "Keep-alive interval for MCP streams." },
    { key: "logRingCapacity", label: "Log ring capacity", help: "How many lines the Logs page keeps in memory." },
    { key: "logRingMaxMessageChars", label: "Log ring max message chars", help: "Cap per line in that ring. Longer lines are truncated there only." },
  ];

  function sourceBadge(key: string) {
    const saved = settings?.sources[key] === "saved";
    return <span className={`source ${saved ? "saved" : "env"}`}>{saved ? "saved" : "from env"}</span>;
  }

  return (
    <div>
      <h1>Configure</h1>
      {err && <p className="err">{err}</p>}

      <h2>Public base URL</h2>
      <p className="muted">
        The URL GitLab can reach. New Project previews webhook URLs from this value.
        {sourceBadge("baseUrl")}
      </p>
      <form onSubmit={saveBase}>
        <input value={draft.baseUrl ?? ""}
          onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })} />
        <p className="callout">
          Webhook URLs: <code className="mono">{`${(draft.baseUrl ?? "").replace(/\/+$/, "")}/webhooks/<forge>/<project-id>`}</code>
        </p>
        <button className="primary" type="submit" disabled={busy}>Save</button>
      </form>

      <h2>Forge hosts</h2>
      <p className="muted">
        Named hosts — kind and URL only, no token, because a PAT is scoped to one project&apos;s
        code and a host is not. New Project can pick one of these or type a custom URL.
        Editing a row does not rewrite existing projects.
      </p>
      <table>
        <thead><tr><th>Name</th><th>Kind</th><th>URL</th><th></th></tr></thead>
        <tbody>
          {gitlabInstances.length === 0 && <tr><td colSpan={4} className="muted">None yet.</td></tr>}
          {gitlabInstances.map((g) => (
            <tr key={g.id}>
              <td>
                <input defaultValue={g.name} key={`${g.id}-name-${g.name}`}
                  onBlur={(e) => { if (e.target.value !== g.name) saveGitlab(g, { name: e.target.value }); }} />
              </td>
              <td>
                <select value={g.kind}
                  onChange={(e) => saveGitlab(g, { kind: e.target.value as ForgeKind })}>
                  {(Object.keys(FORGE_NAMES) as ForgeKind[]).map((k) => (
                    <option key={k} value={k}>{FORGE_NAMES[k]}</option>
                  ))}
                </select>
              </td>
              <td>
                <input defaultValue={g.url} key={`${g.id}-url-${g.url}`}
                  onBlur={(e) => { if (e.target.value !== g.url) saveGitlab(g, { url: e.target.value }); }} />
              </td>
              <td><button className="danger" onClick={() => removeGitlab(g.id)}>Delete</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <form onSubmit={addGitlab}>
        <label>New forge host</label>
        <div className="grid">
          <input placeholder="name" value={gitlabDraft.name}
            onChange={(e) => setGitlabDraft({ ...gitlabDraft, name: e.target.value })} required />
          <select value={gitlabDraft.kind}
            onChange={(e) => setGitlabDraft({ ...gitlabDraft, kind: e.target.value as ForgeKind })}>
            {(Object.keys(FORGE_NAMES) as ForgeKind[]).map((k) => (
              <option key={k} value={k}>{FORGE_NAMES[k]}</option>
            ))}
          </select>
          <input placeholder="https://gitlab.com" value={gitlabDraft.url}
            onChange={(e) => setGitlabDraft({ ...gitlabDraft, url: e.target.value })} required />
          <button className="primary" type="submit">Add</button>
        </div>
      </form>

      <h2>Model provider</h2>
      <p className="muted">
        Named endpoints — provider, model, optional base URL, and the API key projects bill
        against. A project <strong>references</strong> an entry rather than copying it, so
        changing the model or the key here moves every project using it on its next review.
        An entry cannot be deleted while a project still points at it.
      </p>
      <p className="muted">
        Entries you add are yours alone. A <em>shared</em> entry is visible to every account,
        which is why it may not carry a key — only an admin can create one.
      </p>
      <table>
        <thead><tr><th>Name</th><th>Provider</th><th>Model</th><th>Base URL</th><th>API key</th><th></th></tr></thead>
        <tbody>
          {llmProviders.length === 0 && <tr><td colSpan={6} className="muted">None yet.</td></tr>}
          {llmProviders.map((p) => (
            <tr key={p.id}>
              <td>
                <input defaultValue={p.name} key={`${p.id}-name-${p.name}`}
                  onBlur={(e) => { if (e.target.value !== p.name) saveLlm(p, { name: e.target.value }); }} />
              </td>
              <td>
                <select defaultValue={p.provider} key={`${p.id}-provider-${p.provider}`}
                  onChange={(e) => saveLlm(p, { provider: e.target.value as Provider })}>
                  <option>ANTHROPIC</option>
                  <option>OPENAI_COMPAT</option>
                </select>
              </td>
              <td>
                <input defaultValue={p.model} key={`${p.id}-model-${p.model}`}
                  onBlur={(e) => { if (e.target.value !== p.model) saveLlm(p, { model: e.target.value }); }} />
              </td>
              <td>
                <input defaultValue={p.modelBaseUrl ?? ""} key={`${p.id}-base-${p.modelBaseUrl ?? ""}`}
                  placeholder="OpenAI default"
                  onBlur={(e) => {
                    const next = e.target.value;
                    if (next !== (p.modelBaseUrl ?? "")) saveLlm(p, { modelBaseUrl: next });
                  }} />
              </td>
              <td>
                {p.ownerId === null
                  ? <span className="muted">shared — no key</span>
                  : <input type="password" defaultValue="" key={`${p.id}-key`}
                      placeholder={p.modelKey ?? "set a key"}
                      onBlur={(e) => { if (e.target.value) saveLlm(p, { modelKey: e.target.value }); }} />}
              </td>
              <td><button className="danger" onClick={() => removeLlm(p.id)}>Delete</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">
        The key field shows the masked current value as its placeholder and is write-only —
        typing a new one rotates it for every project on that entry; leaving it blank keeps it.
      </p>
      <form onSubmit={addLlm}>
        <label>New LLM provider</label>
        <div className="grid">
          <input placeholder="name" value={llmDraft.name}
            onChange={(e) => setLlmDraft({ ...llmDraft, name: e.target.value })} required />
          <select value={llmDraft.provider}
            onChange={(e) => setLlmDraft({ ...llmDraft, provider: e.target.value as Provider })}>
            <option>ANTHROPIC</option>
            <option>OPENAI_COMPAT</option>
          </select>
          <input placeholder="model id" value={llmDraft.model}
            onChange={(e) => setLlmDraft({ ...llmDraft, model: e.target.value })} required />
          <input placeholder="model base URL (optional)" value={llmDraft.modelBaseUrl}
            onChange={(e) => setLlmDraft({ ...llmDraft, modelBaseUrl: e.target.value })} />
          <input type="password" placeholder={llmDraft.shared ? "shared entries take no key" : "API key"}
            value={llmDraft.modelKey} disabled={llmDraft.shared}
            onChange={(e) => setLlmDraft({ ...llmDraft, modelKey: e.target.value })}
            required={!llmDraft.shared} />
          <button className="primary" type="submit">Add</button>
        </div>
        <div className="toggles">
          <Toggle on={llmDraft.shared}
            onChange={(v) => setLlmDraft({ ...llmDraft, shared: v, modelKey: v ? "" : llmDraft.modelKey })}>
            Shared with everyone (admin only, no key)
          </Toggle>
        </div>
      </form>

      <h2>Settings</h2>
      <p className="muted">
        Live values persist in SQLite and apply without a restart. “from env” is the boot
        default; “saved” is an overlay. Reset to env clears every overlay, including the
        public base URL.
      </p>
      <form onSubmit={save}>
        {fields.map((f) => (
          <div className="setting" key={f.key}>
            <label>
              {f.label}
              {sourceBadge(f.key)}
            </label>
            <p className="muted">{f.help}</p>
            {f.key === "anthropicEffort" ? (
              <select value={draft[f.key] ?? "high"}
                onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}>
                <option value="high">high</option>
                <option value="low">low</option>
              </select>
            ) : (
              <input type={f.type ?? "text"} value={draft[f.key] ?? ""}
                onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })} />
            )}
          </div>
        ))}
        <p className="row" style={{ marginTop: 16 }}>
          <button className="primary" type="submit" disabled={busy}>Save</button>
          <button type="button" onClick={reset} disabled={busy}>Reset to env</button>
        </p>
      </form>

      <h3>Restart-only</h3>
      <p className="muted">Read from the process environment. Changing these needs a restart.</p>
      <table>
        <thead><tr><th>Setting</th><th>Value</th><th></th></tr></thead>
        <tbody>
          {restartOnly.map((row) => (
            <tr key={row.key}>
              <td className="mono">{row.label}</td>
              <td className="mono">{settings.restartOnly[row.key] ?? ""}</td>
              <td className="muted">{row.help}</td>
            </tr>
          ))}
        </tbody>
      </table>

    </div>
  );
}

function SkillLibrary() {
  const [library, setLibrary] = useState<LibrarySkill[]>([]);
  const [libDraft, setLibDraft] = useState({ name: "", body: "" });
  const [err, setErr] = useState<string | null>(null);

  function load() {
    api<LibrarySkill[]>("/api/skills")
      .then(setLibrary)
      .catch((e) => setErr(String((e as Error).message)));
  }
  useEffect(load, []);

  async function addLibrary(e: FormEvent) {
    e.preventDefault();
    await api("/api/skills", { method: "POST", body: JSON.stringify(libDraft) });
    setLibDraft({ name: "", body: "" });
    load();
  }

  async function saveLibrary(s: LibrarySkill, body: string) {
    await api(`/api/skills/${s.id}`, { method: "PUT", body: JSON.stringify({ body }) });
    load();
  }

  async function removeLibrary(id: string) {
    await api(`/api/skills/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <div>
      {err && <p className="err">{err}</p>}
      <h2>Skill library</h2>
      <p className="muted">
        The skills bundled with mrr are copied into every account's library on first sight, as
        your own editable copies — tweak or delete them freely, and a deleted one stays deleted.
      </p>
      <p className="muted">
        Instruction bodies owned by the user behind the API token. A project skill is an
        attachment; editing a body here updates every attached project. Delete is blocked
        while a project still uses the skill.
      </p>
      <table>
        <thead><tr><th>Name</th><th>Owner</th><th></th></tr></thead>
        <tbody>
          {library.length === 0 && <tr><td colSpan={3} className="muted">Empty.</td></tr>}
          {library.map((s) => (
            <tr key={s.id}>
              <td>
                <strong>{s.name}</strong>
                <textarea defaultValue={s.body} key={s.updatedAt}
                  onBlur={(e) => { if (e.target.value !== s.body) saveLibrary(s, e.target.value); }} />
              </td>
              <td>{s.ownerName}</td>
              <td><button className="danger" onClick={() => removeLibrary(s.id)}>Delete</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <form onSubmit={addLibrary}>
        <label>New library skill</label>
        <div className="grid">
          <input placeholder="name" value={libDraft.name}
            onChange={(e) => setLibDraft({ ...libDraft, name: e.target.value })} required />
          <button className="primary" type="submit">Add</button>
        </div>
        <textarea placeholder="instructions" value={libDraft.body}
          onChange={(e) => setLibDraft({ ...libDraft, body: e.target.value })} />
      </form>
    </div>
  );
}

function Stats() {
  const [stats, setStats] = useState<InstanceStats | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api<InstanceStats>("/api/stats")
      .then(setStats)
      .catch((e) => setErr(String((e as Error).message)));
  }, []);

  if (err) return <p className="err">{err}</p>;
  if (!stats) return <p className="muted">Loading…</p>;
  return (
    <div>
      <h2>Stats</h2>
      <p className="muted">Token totals are from this version forward — older reviews show as zero.</p>
      <div className="stats-grid">
        <div className="stat"><strong>{stats.mrsProcessed}</strong><span>MRs processed</span></div>
        <div className="stat"><strong>{stats.tokens.input.toLocaleString()}</strong><span>Tokens in</span></div>
        <div className="stat"><strong>{stats.tokens.output.toLocaleString()}</strong><span>Tokens out</span></div>
        <div className="stat"><strong>{stats.tokens.cacheRead.toLocaleString()}</strong><span>Cache read</span></div>
        <div className="stat"><strong>{stats.tokensLast7Days.input.toLocaleString()}</strong><span>In (7d)</span></div>
        <div className="stat"><strong>{stats.tokensLast7Days.output.toLocaleString()}</strong><span>Out (7d)</span></div>
        <div className="stat"><strong>{stats.findingsOpen}</strong><span>Open findings</span></div>
        <div className="stat"><strong>{stats.findingsAddressed}</strong><span>Addressed</span></div>
        <div className="stat"><strong>{stats.findingsApproved}</strong><span>Approved by hand</span></div>
        <div className="stat"><strong>{stats.inFlight}</strong><span>In flight</span></div>
        <div className="stat"><strong>{stats.pendingOrProcessing}</strong><span>Queued</span></div>
        <div className="stat">
          <strong>{stats.meanReadyDurationSeconds == null ? "—" : `${Math.round(stats.meanReadyDurationSeconds)}s`}</strong>
          <span>Mean READY duration</span>
        </div>
        <div className="stat"><strong>{stats.reviewsTotal}</strong><span>Reviews</span></div>
      </div>
      <p className="muted">
        By state: {Object.entries(stats.reviewsByState).map(([k, v]) => `${k} ${v}`).join(" · ") || "none"}
        {Object.keys(stats.failuresByKind).length > 0
          ? ` · Failures: ${Object.entries(stats.failuresByKind).map(([k, v]) => `${k} ${v}`).join(", ")}`
          : ""}
      </p>
      <table>
        <thead>
          <tr><th>Project</th><th>MRs</th><th>Reviews</th><th>Tokens in</th><th>Tokens out</th></tr>
        </thead>
        <tbody>
          {stats.projects.length === 0 && (
            <tr><td colSpan={5} className="muted">No projects yet.</td></tr>
          )}
          {stats.projects.map((p) => (
            <tr key={p.projectId}>
              <td><a href={`/projects/${p.projectId}`} onClick={(e) => { e.preventDefault(); go(`/projects/${p.projectId}`); }}>{p.name}</a></td>
              <td>{p.mrs}</td>
              <td>{p.reviews}</td>
              <td>{p.inputTokens.toLocaleString()}</td>
              <td>{p.outputTokens.toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
