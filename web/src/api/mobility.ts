import { get, patch, post } from './client';
import type { Model } from './types';

export type MobilityView = Model<'MobilityView'>;
export type CampusAnchor = Model<'CampusAnchor'>;

export const getMobility = () => get<MobilityView>('/mobility/session');
export const getTravelModels = () => get<Model<'MobilityTravelModels'>>('/mobility/travel-models');
export const rollbackTravelModel = (model: Model<'MobilityTravelModel'>) =>
  post<Model<'MobilityTravelModel'>>(`/mobility/travel-models/${encodeURIComponent(model.fromBuildingId)}/${encodeURIComponent(model.toBuildingId)}/rollback`,
    {expectedVersion:model.version} satisfies Model<'MobilityModelRollbackRequest'>);
export const saveAnchor = (body: Model<'CampusAnchorRequest'>, id?: string) => id
  ? patch<CampusAnchor>(`/mobility/anchors/${encodeURIComponent(id)}`, body)
  : post<CampusAnchor>('/mobility/anchors', body);
export const stopMobility = (session: Model<'MobilitySession'>, action: 'pause' | 'end') =>
  post<MobilityView>(`/mobility/sessions/${encodeURIComponent(session.id)}/${action}`, {
    sourceEpoch: session.sourceEpoch,
    expectedVersion: session.version,
  } satisfies Model<'MobilityActionRequest'>);
