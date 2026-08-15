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
  lastErrorKind: string | null;
  lastError: string | null;
  lastErrorAt: string | null;
};
type MrStatus = {
  projectId: string;
  mrIid: number;
  currentStatus: string;
  latestReviewId: string | null;
  latestHeadSha: string | null;
  openFindings: number;
  mrrApproved: boolean;
  updatedAt: string;
};
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
  mr: { title: string | null; url: string | null };
  prompt: string | null;
  response: string | null;
  error: string | null;
  errorKind: string | null;
  createdAt: string;
  agentTurns: number;
  trace: AgentTrace | null;
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
  | { name: "login" }
  | { name: "projects" }
  | { name: "project"; id: string }
  | { name: "edit"; id: string }
  | { name: "new" }
  | { name: "mr"; id: string; iid: string }
  | { name: "review"; id: string }
  | { name: "admin" }
  | { name: "logs" };

function parseRoute(path: string): Route {
  if (path === "/" || path === "/login") return { name: "login" };
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
  return { name: "projects" };
}

function go(path: string) {
  window.history.pushState({}, "", path);
  window.dispatchEvent(new PopStateEvent("popstate"));
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
  const cls = s === "CLEAN" || s === "READY" || s === "ADDRESSED" ? "ok"
    : s === "NEEDS_WORK" || s === "REVIEWING" || s === "PENDING" || s === "PROCESSING" ? "warn"
    : s === "FAILED" || s === "CANCELLED" || s === "BLOCKER" || s === "QUOTA" || s === "PROVIDER" || s === "AUTH" || s === "MCP" || s === "GITLAB" || s === "RATE_LIMIT" || s === "REFUSAL" ? "bad"
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

export default function App() {
  const [path, setPath] = useState("/login");
  const [me, setMe] = useState<Me | null>(null);
  const [boot, setBoot] = useState(true);

  useEffect(() => {
    const sync = () => setPath(window.location.pathname);
    sync();
    window.addEventListener("popstate", sync);
    return () => window.removeEventListener("popstate", sync);
  }, []);

  useEffect(() => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      setBoot(false);
      return;
    }
    api<Me>("/api/me")
      .then((user) => {
        setMe(user);
        if (window.location.pathname === "/" || window.location.pathname === "/login") {
          go("/projects");
        }
      })
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        setMe(null);
      })
      .finally(() => setBoot(false));
  }, []);

  const route = useMemo(() => parseRoute(path), [path]);

  if (boot) return <div className="wrap muted">Loading…</div>;

  if (!me || route.name === "login") {
    return <Login onLogin={(user) => { setMe(user); go("/projects"); }} />;
  }

  return (
    <div className={route.name === "logs" ? "wrap wide" : "wrap"}>
      <div className="top">
        <h1>mrr</h1>
        <nav>
          <a href="/projects" onClick={(e) => { e.preventDefault(); go("/projects"); }}>Projects</a>
          {me.role === "ADMIN" && (
            <>
              <a href="/logs" onClick={(e) => { e.preventDefault(); go("/logs"); }}>Logs</a>
              <a href="/admin" onClick={(e) => { e.preventDefault(); go("/admin"); }}>Admin</a>
            </>
          )}
        </nav>
        <span className="muted" style={{ marginLeft: "auto" }}>{me.name} · {me.role}</span>
        <button onClick={() => { localStorage.removeItem(TOKEN_KEY); setMe(null); go("/login"); }}>
          Sign out
        </button>
      </div>
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

function Login({ onLogin }: { onLogin: (me: Me) => void }) {
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
    <div className="wrap" style={{ maxWidth: 420 }}>
      <h1>mrr</h1>
      <p className="muted">Paste an API token. There is no password login.</p>
      <form onSubmit={submit}>
        <label>Token</label>
        <input value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" />
        {err && <p className="err">{err}</p>}
        <p><button className="primary" type="submit">Continue</button></p>
      </form>
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
      <div className="row">
        <h2 style={{ margin: 0 }}>Projects</h2>
        <button className="primary" onClick={() => go("/projects/new")}>Register</button>
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
  const [mrs, setMrs] = useState<MrStatus[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      api<Project>(`/api/projects/${id}`),
      api<MrStatus[]>(`/api/projects/${id}/merge-requests`),
    ]).then(([p, list]) => { setProject(p); setMrs(list); })
      .catch((e) => setErr(String(e.message)));
  }, [id]);

  if (err) return <p className="err">{err}</p>;
  if (!project || !mrs) return <p className="muted">Loading…</p>;

  return (
    <div>
      <div className="row">
        <h2 style={{ margin: 0 }}>{project.name}</h2>
        <button onClick={() => go(`/projects/${id}/edit`)}>Edit</button>
      </div>
      <p className="muted">
        {project.gitlabUrl}/{project.gitlabProjectId} · {project.model}
        {project.autoApprove ? " · auto-approve on" : ""} · turns {project.agentMaxTurns}
        {project.agentTools.length ? ` · tools ${project.agentTools.join(",")}` : ""}
      </p>
      <FailureBanner kind={project.lastErrorKind} message={project.lastError} />
      <AgentContext projectId={id} tools={project.agentTools} />
      <h2>Merge requests</h2>
      {mrs.length === 0 && <p className="muted">No reviews yet.</p>}
      <table>
        <thead>
          <tr><th>!iid</th><th>Status</th><th>Open</th><th>Approved</th><th>SHA</th></tr>
        </thead>
        <tbody>
          {mrs.map((mr) => (
            <tr key={mr.mrIid}>
              <td>
                <a href={`/projects/${id}/mrs/${mr.mrIid}`}
                  onClick={(e) => { e.preventDefault(); go(`/projects/${id}/mrs/${mr.mrIid}`); }}>
                  !{mr.mrIid}
                </a>
              </td>
              <td>{pill(mr.currentStatus)}</td>
              <td>{mr.openFindings}</td>
              <td>{mr.mrrApproved ? "mrr" : "—"}</td>
              <td className="muted">{mr.latestHeadSha?.slice(0, 8)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AgentContext({ projectId, tools }: { projectId: string; tools: string[] }) {
  const [skills, setSkills] = useState<AgentSkill[] | null>(null);
  const [docs, setDocs] = useState<AgentDocument[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [draftSkill, setDraftSkill] = useState({ name: "", body: "" });
  const [draftDoc, setDraftDoc] = useState({ name: "", body: "" });
  const [editing, setEditing] = useState<{ type: "skill" | "doc"; id: string; body: string } | null>(null);

  function load() {
    Promise.all([
      api<AgentSkill[]>(`/api/projects/${projectId}/skills`),
      api<AgentDocument[]>(`/api/projects/${projectId}/documents`),
    ]).then(([s, d]) => { setSkills(s); setDocs(d); })
      .catch((e) => setErr(String(e.message)));
  }
  useEffect(load, [projectId]);

  async function toggleSkill(s: AgentSkill) {
    await api(`/api/projects/${projectId}/skills/${s.id}`, {
      method: "PUT", body: JSON.stringify({ enabled: !s.enabled }),
    });
    load();
  }
  async function toggleDoc(d: AgentDocument) {
    await api(`/api/projects/${projectId}/documents/${d.id}`, {
      method: "PUT", body: JSON.stringify({ enabled: !d.enabled }),
    });
    load();
  }
  async function addSkill(e: FormEvent) {
    e.preventDefault();
    await api(`/api/projects/${projectId}/skills`, {
      method: "POST", body: JSON.stringify({ ...draftSkill, enabled: true }),
    });
    setDraftSkill({ name: "", body: "" });
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
    await api(path, { method: "PUT", body: JSON.stringify({ body: editing.body }) });
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
  if (!skills || !docs) return <p className="muted">Loading agent context…</p>;

  return (
    <div>
      <h2>Agent skills</h2>
      <p className="muted">
        Enabled skills are concatenated into the system prompt, the same way tools are a set you turn on.
        {tools.length ? ` Tools on: ${tools.join(", ")}.` : " No tools enabled — set them under Edit."}
      </p>
      {skills.length === 0 && <p className="muted">None yet — the built-in reviewer prompt is used.</p>}
      {skills.map((s) => (
        <div className="item" key={s.id}>
          <div className="row">
            <label style={{ margin: 0 }}>
              <input type="checkbox" checked={s.enabled} onChange={() => toggleSkill(s)} /> {s.name}
            </label>
            <button onClick={() => setEditing({ type: "skill", id: s.id, body: s.body })}>Edit</button>
            <button className="danger" onClick={() => remove("skill", s.id)}>Delete</button>
          </div>
          {editing?.type === "skill" && editing.id === s.id && (
            <div>
              <textarea value={editing.body} onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
              <p className="row">
                <button className="primary" onClick={saveEdit}>Save</button>
                <button onClick={() => setEditing(null)}>Cancel</button>
              </p>
            </div>
          )}
        </div>
      ))}
      <form onSubmit={addSkill}>
        <label>Add skill</label>
        <div className="grid">
          <input placeholder="name (e.g. security)" value={draftSkill.name}
            onChange={(e) => setDraftSkill({ ...draftSkill, name: e.target.value })} required />
          <button className="primary" type="submit">Add</button>
        </div>
        <textarea placeholder="instructions" value={draftSkill.body}
          onChange={(e) => setDraftSkill({ ...draftSkill, body: e.target.value })} />
      </form>

      <h2>Documents</h2>
      <p className="muted">Reference material (architecture notes, conventions). Enabled files are appended to the review prompt.</p>
      {docs.length === 0 && <p className="muted">Folder is empty.</p>}
      {docs.map((d) => (
        <div className="item" key={d.id}>
          <div className="row">
            <label style={{ margin: 0 }}>
              <input type="checkbox" checked={d.enabled} onChange={() => toggleDoc(d)} /> {d.name}
            </label>
            <button onClick={() => setEditing({ type: "doc", id: d.id, body: d.body })}>Edit</button>
            <button className="danger" onClick={() => remove("doc", d.id)}>Delete</button>
          </div>
          {editing?.type === "doc" && editing.id === d.id && (
            <div>
              <textarea value={editing.body} onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
              <p className="row">
                <button className="primary" onClick={saveEdit}>Save</button>
                <button onClick={() => setEditing(null)}>Cancel</button>
              </p>
            </div>
          )}
        </div>
      ))}
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
  );
}

function ProjectForm({ id }: { id?: string }) {
  const [form, setForm] = useState({
    name: "", gitlabUrl: "https://gitlab.com", gitlabProjectId: "", gitlabToken: "",
    provider: "ANTHROPIC", modelBaseUrl: "", model: "", modelKey: "", skill: "",
    skipDrafts: false, agentMaxTurns: 1, agentTools: "", autoApprove: false, enabled: true,
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
      <h2>{id ? "Edit project" : "Register project"}</h2>
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
      <div className="grid">
        <div>
          <label>Agent max turns</label>
          <input type="number" min={1} value={form.agentMaxTurns}
            onChange={(e) => set("agentMaxTurns", Number(e.target.value))} />
        </div>
        <div>
          <label>Agent tools</label>
          <label style={{ marginTop: 8 }}>
            <input type="checkbox" checked={form.agentTools.split(",").map((s) => s.trim()).includes("read_file")}
              onChange={(e) => set("agentTools", e.target.checked ? "read_file" : "")} /> read_file
          </label>
        </div>
      </div>
      <p className="row">
        <label><input type="checkbox" checked={form.skipDrafts} onChange={(e) => set("skipDrafts", e.target.checked)} /> Skip drafts</label>
        <label><input type="checkbox" checked={form.autoApprove} onChange={(e) => set("autoApprove", e.target.checked)} /> Auto-approve when clean</label>
        {id && <label><input type="checkbox" checked={form.enabled} onChange={(e) => set("enabled", e.target.checked)} /> Enabled</label>}
      </p>
      {err && <p className="err">{err}</p>}
      <button className="primary" type="submit">{id ? "Save" : "Register"}</button>
    </form>
  );
}

function MrCycle({ projectId, iid }: { projectId: string; iid: string }) {
  const [cycle, setCycle] = useState<Cycle | null>(null);
  const [err, setErr] = useState<string | null>(null);

  function load() {
    api<Cycle>(`/api/projects/${projectId}/merge-requests/${iid}`)
      .then(setCycle).catch((e) => setErr(String(e.message)));
  }
  useEffect(load, [projectId, iid]);

  async function rerun() {
    if (!cycle?.status.latestReviewId) return;
    await api(`/api/reviews/${cycle.status.latestReviewId}/rerun`, { method: "POST" });
    load();
  }

  if (err) return <p className="err">{err}</p>;
  if (!cycle) return <p className="muted">Loading…</p>;

  const title = cycle.reviews[0]?.mr?.title;
  return (
    <div>
      <div className="row">
        <h2 style={{ margin: 0 }}>!{iid} {title}</h2>
        {pill(cycle.status.currentStatus)}
        {cycle.status.mrrApproved && <span className="pill ok">mrr approved</span>}
        {cycle.reviews[0]?.mr?.url && <a href={cycle.reviews[0].mr.url} target="_blank" rel="noreferrer">GitLab</a>}
        {cycle.status.latestReviewId && <button onClick={rerun}>Re-run latest</button>}
      </div>
      <p className="muted">{cycle.status.openFindings} open finding(s)</p>
      {cycle.reviews.some((r) => r.state === "FAILED") && (
        <FailureBanner
          kind={cycle.reviews.find((r) => r.state === "FAILED")?.errorKind}
          message={cycle.reviews.find((r) => r.state === "FAILED")?.error}
        />
      )}
      <h2>Findings</h2>
      {cycle.findings.length === 0 && <p className="muted">None.</p>}
      {cycle.findings.map((f) => (
        <div className="card" key={f.id}>
          <div className="row">
            {pill(f.severity)} {pill(f.state)}
            <strong>{f.title}</strong>
            <span className="muted">{f.filePath}{f.line ? `:${f.line}` : ""}</span>
          </div>
        </div>
      ))}
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

  useEffect(() => {
    api<Review>(`/api/reviews/${id}`).then(setReview).catch((e) => setErr(String(e.message)));
  }, [id]);

  if (err) return <p className="err">{err}</p>;
  if (!review) return <p className="muted">Loading…</p>;

  return (
    <div>
      <div className="row">
        <h2 style={{ margin: 0 }}>{review.mr?.title ?? review.id}</h2>
        {pill(review.state)}
      </div>
      <p className="muted">
        !{review.mrIid} · {review.headSha.slice(0, 8)} · {review.createdAt}
        {review.mr?.url && <> · <a href={review.mr.url} target="_blank" rel="noreferrer">GitLab</a></>}
      </p>
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

function Trail({ trace }: { trace: AgentTrace | null }) {
  if (!trace || !trace.events?.length) {
    return (
      <>
        <h2>Trail</h2>
        <p className="muted">No tool turns — single shot.</p>
      </>
    );
  }
  return (
    <>
      <h2>Trail</h2>
      <p className="muted">{trace.turns}/{trace.maxTurns} turns</p>
      {trace.events.map((e, i) => (
        <div className="card" key={i}>
          <div className="row">
            {pill(e.role)}
            <span className="muted">turn {e.turn}</span>
            {e.stopReason && <span className="muted">{e.stopReason}</span>}
            {e.elapsedMs != null && <span className="muted">{e.elapsedMs}ms</span>}
            {e.name && <span className="muted">{e.name}</span>}
            {e.truncated && pill("truncated")}
          </div>
          {e.toolCalls && e.toolCalls.length > 0 && (
            <pre>{e.toolCalls.map((c) => `${c.name} ${c.arguments}`).join("\n")}</pre>
          )}
          {e.text && <pre>{e.text}</pre>}
          {e.result && <pre>{e.result}</pre>}
        </div>
      ))}
    </>
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
    else if (lower.startsWith("tool") || lower === "read_file" || lower === "openai_compat" || lower === "anthropic") cls = "log-tok-tool";
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
        <label style={{ margin: 0 }}>
          <input type="checkbox" checked={mrrOnly} onChange={(e) => setMrrOnly(e.target.checked)} /> mrr only
        </label>
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
        (truncated at 16k here).
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
            <span className="log-time">{fmt(l.at)}</span>
            <span className="log-lvl">{l.level}</span>
            <span className="log-logger" title={l.logger}>{shortLogger(l.logger)}</span>
            <span className="log-msg">{highlightLog(l.message)}</span>
            {l.thrown && <span className="log-thrown">{l.thrown}</span>}
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
