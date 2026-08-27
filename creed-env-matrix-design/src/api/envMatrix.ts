import { request, requestAllowing422, toQuery } from './client';
import type {
  AppLink,
  AppLinkRequest,
  BatchSaveResponse,
  ConflictGroup,
  Dimensions,
  Endpoint,
  EndpointFilter,
  EndpointRequest,
  HealthReport,
  LinkBatchSaveResponse,
  MatrixResponse,
} from './types';

/** Every read route takes the same filter, so it is serialised in one place. */
function filterQuery(filter: EndpointFilter): string {
  return toQuery({
    appSystem: filter.appSystem,
    tier: filter.tier,
    envInstance: filter.envInstance,
    country: filter.country,
    service: filter.service,
    instance: filter.instance,
    scheme: filter.scheme,
    keyword: filter.keyword,
  });
}

export const envMatrixApi = {
  ping: () =>
    request<{ service: string; status: string; healthProbeMode: string; time: string }>('/ping'),

  /** Filter options, derived server-side from the rows that exist. */
  dimensions: () => request<Dimensions>('/dimensions'),

  endpoints: (filter: EndpointFilter = {}) =>
    request<Endpoint[]>(`/endpoints${filterQuery(filter)}`),

  matrix: (filter: EndpointFilter = {}) => request<MatrixResponse>(`/matrix${filterQuery(filter)}`),

  conflicts: (filter: EndpointFilter = {}) =>
    request<ConflictGroup[]>(`/conflicts${filterQuery(filter)}`),

  health: (filter: EndpointFilter = {}) => request<HealthReport>(`/health${filterQuery(filter)}`),

  /** Re-runs the probe. In mock mode this rotates the seed, so states visibly change. */
  recheckHealth: () =>
    request<{ mode: string; mocked: boolean; seed: number; checkedAt: string }>('/health/recheck', {
      method: 'POST',
    }),

  /**
   * Declared app-system links. `tier` is optional on the wire so the config editor can load
   * everything, but the topology page always passes one — the wiring is declared per tier, and an
   * unscoped graph would overlay four environments' topologies on top of each other.
   */
  links: (tier?: string) => request<AppLink[]>(`/links${tier ? toQuery({ tier }) : ''}`),

  createLink: (payload: AppLinkRequest) =>
    request<AppLink>('/links', { method: 'POST', body: JSON.stringify(payload) }),

  updateLink: (id: number, payload: AppLinkRequest) =>
    request<AppLink>(`/links/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),

  removeLink: (id: number) => request<void>(`/links/${id}`, { method: 'DELETE' }),

  /**
   * Replaces one tier's wiring in a single transaction.
   *
   * Unlike the endpoint batch save this is always authoritative, but only for the named tier: links
   * in *other* tiers are never touched. That is why the link editor can work on one tier at a time
   * while the endpoint editor has to hold the whole table.
   */
  batchSaveLinks: (tier: string, links: AppLinkRequest[]) =>
    requestAllowing422<LinkBatchSaveResponse>('/links', {
      method: 'PUT',
      body: JSON.stringify({ tier, links }),
    }),

  create: (payload: EndpointRequest) =>
    request<Endpoint>('/endpoints', { method: 'POST', body: JSON.stringify(payload) }),

  update: (id: number, payload: EndpointRequest) =>
    request<Endpoint>(`/endpoints/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),

  remove: (id: number) => request<void>(`/endpoints/${id}`, { method: 'DELETE' }),

  /**
   * Writes the whole edited table back in one transaction.
   *
   * `deleteMissing` makes the payload authoritative — anything not in it is deleted — which is what
   * makes "remove a row, then save" work. The config page therefore always loads the complete,
   * unfiltered set before enabling save; sending a filtered subset with this flag would delete
   * everything the filter hid. A 422 is a validation outcome, not a transport error: `issues` is
   * populated and nothing was written.
   */
  batchSave: (endpoints: EndpointRequest[], deleteMissing: boolean) =>
    requestAllowing422<BatchSaveResponse>('/endpoints', {
      method: 'PUT',
      body: JSON.stringify({ endpoints, deleteMissing }),
    }),
};
