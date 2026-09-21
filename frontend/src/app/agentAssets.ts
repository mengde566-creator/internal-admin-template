import { warehouseAgentAsset } from '../modules/warehouse/agent/warehouseAgentAsset'
import type { AgentFrontendAsset } from '../modules/agent/registry'

/** Static production composition root: every backend adapter has an explicit frontend asset. */
export const agentFrontendAssets: AgentFrontendAsset[] = [warehouseAgentAsset]
