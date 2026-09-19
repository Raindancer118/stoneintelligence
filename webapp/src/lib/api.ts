import { getAccessToken } from "./auth";

const baseUrl = import.meta.env.VITE_PLATFORM_API_URL as string;

export interface Vault {
  id: string;
  name: string;
  createdAt: string;
}

export interface Role {
  id: string;
  name: string;
  permissions: string[];
}

export interface Group {
  id: string;
  name: string;
  memberSubjects: string[];
  roleIds: string[];
}

export interface PathRule {
  pathPrefix: string;
  scopeSubject: string | null;
  effect: "ALLOW" | "DENY";
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = await getAccessToken();
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      Authorization: `Bearer ${token}`,
      ...options.headers,
    },
  });
  if (!response.ok) {
    throw new Error(`${options.method ?? "GET"} ${path} failed: HTTP ${response.status}`);
  }
  if (response.status === 204 || response.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  const body = await response.text();
  return (body.length === 0 ? undefined : JSON.parse(body)) as T;
}

export const api = {
  listVaults: () => request<Vault[]>("/api/v1/vaults"),
  createVault: (name: string) => request<Vault>("/api/v1/vaults", { method: "POST", body: JSON.stringify({ name }) }),

  listRoles: (vaultId: string) => request<Role[]>(`/api/v1/vaults/${vaultId}/roles`),
  createRole: (vaultId: string, name: string, permissions: string[]) =>
    request<Role>(`/api/v1/vaults/${vaultId}/roles`, { method: "POST", body: JSON.stringify({ name, permissions }) }),

  listGroups: (vaultId: string) => request<Group[]>(`/api/v1/vaults/${vaultId}/groups`),
  createGroup: (vaultId: string, name: string) =>
    request<Group>(`/api/v1/vaults/${vaultId}/groups`, { method: "POST", body: JSON.stringify({ name }) }),
  addMember: (vaultId: string, groupId: string, subject: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/members`, {
      method: "POST",
      body: JSON.stringify({ subject }),
    }),
  removeMember: (vaultId: string, groupId: string, subject: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/members/${encodeURIComponent(subject)}`, {
      method: "DELETE",
    }),
  assignRole: (vaultId: string, groupId: string, roleId: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/roles/${roleId}`, { method: "POST" }),
  unassignRole: (vaultId: string, groupId: string, roleId: string) =>
    request<void>(`/api/v1/vaults/${vaultId}/groups/${groupId}/roles/${roleId}`, { method: "DELETE" }),

  listPathRules: (vaultId: string) => request<PathRule[]>(`/api/v1/vaults/${vaultId}/path-rules`),
  createPathRule: (vaultId: string, pathPrefix: string, scopeSubject: string | null, effect: "ALLOW" | "DENY") =>
    request<PathRule>(`/api/v1/vaults/${vaultId}/path-rules`, {
      method: "POST",
      body: JSON.stringify({ pathPrefix, scopeSubject, effect }),
    }),
};
