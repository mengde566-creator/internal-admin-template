import { computed, ref } from 'vue'
import {
  fetchDepartmentOptionsApi,
  type DepartmentNode,
} from '../../iam/api/department'
import {
  fetchLocationOptions,
  fetchLocations,
  fetchWarehouseItems,
  fetchWarehouseOptions,
  fetchWarehouses,
  type Item,
  type Location,
  type Warehouse,
} from '../api/warehouse'

export function useWarehouseReferences(defaultOptions: { withDepartments?: boolean } = {}) {
  const items = ref<Item[]>([])
  const warehouses = ref<Warehouse[]>([])
  const warehouseOptions = ref<Warehouse[]>([])
  const locations = ref<Location[]>([])
  const departments = ref<DepartmentNode[]>([])
  const loading = ref(false)
  const error = ref('')
  const enabledItems = computed(() => items.value.filter((item) => item.enabled))
  const enabledWarehouses = computed(() => warehouseOptions.value.filter((warehouse) => warehouse.enabled))
  const enabledLocations = computed(() => locations.value.filter((location) => location.enabled))
  const departmentOptions = computed(() => flattenDepartments(departments.value))

  async function loadReferences(options: { withDepartments?: boolean } = defaultOptions) {
    loading.value = true
    error.value = ''
    try {
      const responses = await Promise.all([
        fetchWarehouseItems(),
        fetchWarehouses(),
        fetchWarehouseOptions(),
        fetchLocationOptions(),
        options.withDepartments ? fetchDepartmentOptionsApi() : Promise.resolve(null),
      ])
      items.value = responses[0].data.data
      warehouses.value = responses[1].data.data
      warehouseOptions.value = responses[2].data.data
      locations.value = responses[3].data.data
      if (responses[4]) departments.value = responses[4].data.data.nodes
      return true
    } catch (cause: any) {
      error.value = messageOf(cause, '仓储数据加载失败，请稍后重试')
      return false
    } finally {
      loading.value = false
    }
  }

  async function loadWarehouseLocations(warehouseId: string) {
    if (!warehouseId) return []
    try {
      const rows = (await fetchLocations(warehouseId)).data.data
      locations.value = [...locations.value.filter((location) => location.warehouseId !== warehouseId), ...rows]
      return rows
    } catch (cause: any) {
      error.value = messageOf(cause, '库位加载失败，请稍后重试')
      return []
    }
  }

  return {
    items,
    warehouses,
    warehouseOptions,
    locations,
    departments,
    loading,
    error,
    enabledItems,
    enabledWarehouses,
    enabledLocations,
    departmentOptions,
    loadReferences,
    loadWarehouseLocations,
  }
}

export function flattenDepartments(nodes: DepartmentNode[]): DepartmentNode[] {
  return nodes.flatMap((node) => [node, ...flattenDepartments(node.children || [])])
}

export function messageOf(errorLike: any, fallback: string) {
  return errorLike?.response?.data?.message ?? fallback
}

export function itemLabel(items: Item[], id: string) {
  const item = items.find((row) => row.id === id)
  return item ? `${item.code} / ${item.name}` : '未知物品'
}

export function locationLabel(locations: Location[], id: string) {
  const location = locations.find((row) => row.id === id)
  return location ? `${location.code} / ${location.name}` : '未知库位'
}

export function warehouseLabel(warehouses: Warehouse[], id: string) {
  return warehouses.find((row) => row.id === id)?.name ?? '未知仓库'
}
