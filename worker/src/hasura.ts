/// <reference types="@cloudflare/workers-types" />
/** Minimal Hasura GraphQL client. Admin secret stays server-side (Worker secret). */

export interface HasuraEnv {
  HASURA_GRAPHQL_URL: string;
  HASURA_ADMIN_SECRET: string;
  HASURA_ROLE?: string;
}

/**
 * Run a GraphQL operation against Hasura with the admin secret.
 * `useRole` sends x-hasura-role (least-privilege for writes); omit for admin reads.
 */
export async function hasura<T = unknown>(
  env: HasuraEnv,
  query: string,
  variables: Record<string, unknown> = {},
  useRole = false
): Promise<T> {
  const headers: Record<string, string> = {
    'content-type': 'application/json',
    'x-hasura-admin-secret': env.HASURA_ADMIN_SECRET,
  };
  if (useRole && env.HASURA_ROLE) headers['x-hasura-role'] = env.HASURA_ROLE;

  const resp = await fetch(env.HASURA_GRAPHQL_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify({ query, variables }),
  });
  if (!resp.ok) throw new Error(`hasura http ${resp.status}`);
  const body = (await resp.json()) as { data?: T; errors?: Array<{ message?: string }> };
  if (body.errors && body.errors.length) {
    throw new Error(`hasura: ${body.errors[0]?.message ?? 'graphql error'}`);
  }
  return body.data as T;
}
