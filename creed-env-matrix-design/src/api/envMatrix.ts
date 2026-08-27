import { request, requestAllowing422, toQuery } from './client';
import type {
  BatchSaveResponse,
  ConflictGroup,
  Dimensions,
  Endpoint,
  EndpointFilter,
  EndpointRequest,
  HealthReport,
  MatrixResponse,
  Release,
  ReleaseRequest,
  ReleaseStatus,
  ReleaseTopology,
  ReleaseTopologyRequest,
  ReleaseTopologySaveResponse,
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

  /** Releases — the named sets of environment slices the topology graph is scoped to. */
  releases: (filter: { tier?: string; status?: ReleaseStatus } = {}) =>
    request<Release[]>(`/releases${toQuery({ tier: filter.tier, status: filter.status })}`),

  createRelease: (payload: ReleaseRequest) =>
    request<Release>('/releases', { method: 'POST', body: JSON.stringify(payload) }),

  updateRelease: (id: number, payload: ReleaseRequest) =>
    request<Release>(`/releases/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),

  /** Takes the release's participants and links with it. */
  removeRelease: (id: number) => request<void>(`/releases/${id}`, { method: 'DELETE' }),

  /**
   * Participants and links in one response.
   *
   * Fetched together because a link is meaningless without its two ends — two requests would let
   * the graph render half-built while the second is still in flight.
   */
  releaseTopology: (id: number) => request<ReleaseTopology>(`/releases/${id}/topology`),

  /**
   * Replaces one release's topology in a single transaction.
   *
   * Authoritative for that release only: anything absent is deleted, no other release is touched.
   * A link may point at a participant created in the same payload via `{ ref }` — see `NodeRef`.
   */
  saveReleaseTopology: (id: number, payload: ReleaseTopologyRequest) =>
    requestAllowing422<ReleaseTopologySaveResponse>(`/releases/${id}/topology`, {
      method: 'PUT',
      body: JSON.stringify(payload),
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
