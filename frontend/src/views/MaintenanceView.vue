<template>
  <div>
    <!-- 费用统计 -->
    <div class="sum-grid" style="margin-bottom: 14px">
      <div class="sum-card"><div class="label">维修单总数</div><div class="value">{{ summary.total }}</div></div>
      <div class="sum-card"><div class="label">待修</div><div class="value">{{ summary.waiting }}</div></div>
      <div class="sum-card accent"><div class="label">维修中</div><div class="value">{{ summary.repairing }}</div></div>
      <div class="sum-card"><div class="label">已完工</div><div class="value">{{ summary.done }}</div></div>
      <div class="sum-card accent"><div class="label">配件费合计</div><div class="value">¥{{ summary.partsFeeTotal }}</div></div>
      <div class="sum-card accent"><div class="label">工时费合计</div><div class="value">¥{{ summary.laborFeeTotal }}</div></div>
      <div class="sum-card accent"><div class="label">总费用</div><div class="value">¥{{ summary.totalFee }}</div></div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">维修保养单</span>
        <span class="panel-extra">总费用 = 工时 × 80 元/小时 + 配件费 · 同车同时只能有一张未结工单，全部完工后车辆才恢复可用</span>
        <el-button type="danger" size="small" style="margin-left: auto" @click="dialog = true">＋ 开维修单</el-button>
      </div>
      <el-table :data="rows" size="small" stripe>
        <el-table-column prop="orderNo" label="维修单号" width="120" />
        <el-table-column prop="kartNo" label="车号" width="70" />
        <el-table-column label="车辆状态" width="88">
          <template #default="{ row }">
            <span class="tag" :class="row.kartStatus === 'AVAILABLE' ? 'ok' : row.kartStatus === 'MAINTENANCE' ? 'warn' : 'mute'">
              {{ row.kartStatusName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ row.maintTypeName }}</template>
        </el-table-column>
        <el-table-column prop="faultDesc" label="故障描述" min-width="180" show-overflow-tooltip />
        <el-table-column prop="workHours" label="工时" width="70" />
        <el-table-column label="配件费" width="90">
          <template #default="{ row }">¥{{ row.partsFee }}</template>
        </el-table-column>
        <el-table-column label="总费用" width="100">
          <template #default="{ row }"><b>¥{{ row.totalFee }}</b></template>
        </el-table-column>
        <el-table-column prop="worker" label="维修人" width="90" />
        <el-table-column prop="startTime" label="开始时间" width="165" />
        <el-table-column prop="endTime" label="结束时间" width="165" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="tag" :class="row.status === 'DONE' ? 'ok' : row.status === 'REPAIRING' ? 'warn' : 'mute'">
              {{ row.statusName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'WAITING'"
              link
              type="warning"
              :loading="actingId === row.id"
              :disabled="actingId !== null"
              @click="start(row)"
            >开工</el-button>
            <el-button
              v-if="row.status === 'REPAIRING'"
              link
              type="success"
              :loading="actingId === row.id"
              :disabled="actingId !== null"
              @click="complete(row)"
            >完工</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="开立维修单" width="440">
      <el-alert
        v-if="openKartIds.size > 0"
        type="warning"
        :closable="false"
        style="margin-bottom: 12px"
        title="同一辆车同时只能有一张未结工单；已有未结工单的车辆已在下拉中置灰，需先将其完工"
      />
      <el-form label-width="90px">
        <el-form-item label="维修单号"><el-input v-model="form.orderNo" placeholder="例如 MT-20260913" /></el-form-item>
        <el-form-item label="车辆">
          <el-select v-model="form.kartId" style="width: 100%">
            <el-option
              v-for="k in karts"
              :key="k.id"
              :label="k.kartNo + ' · ' + k.modelName + (openOrderText(k.id) ? '（未结工单 ' + openOrderText(k.id) + '）' : '')"
              :value="k.id"
              :disabled="openKartIds.has(k.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="维修类型">
          <el-select v-model="form.maintType" style="width: 100%">
            <el-option label="换机油" value="OIL" />
            <el-option label="换轮胎" value="TIRE" />
            <el-option label="刹车片" value="BRAKE" />
            <el-option label="链条" value="CHAIN" />
            <el-option label="火花塞" value="SPARK" />
            <el-option label="发动机大修" value="ENGINE" />
            <el-option label="车身修复" value="BODY" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障描述"><el-input v-model="form.faultDesc" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="工时"><el-input v-model.number="form.workHours" /></el-form-item>
        <el-form-item label="配件费"><el-input v-model.number="form.partsFee" /></el-form-item>
        <el-form-item label="维修人"><el-input v-model="form.worker" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" :disabled="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { kartApi, maintenanceApi } from '../api'

const track = inject('track')
const rows = ref([])
const karts = ref([])
const summary = ref({ total: 0, waiting: 0, repairing: 0, done: 0, partsFeeTotal: 0, laborFeeTotal: 0, totalFee: 0 })
const dialog = ref(false)
const form = ref({ orderNo: '', kartId: null, maintType: 'OIL', faultDesc: '', workHours: 1, partsFee: 180, worker: '' })
/** 正在处理中的工单 id：请求未返回前禁用所有开工/完工按钮，防止重复点击 */
const actingId = ref(null)
/** 开单提交中：防止连点保存产生重复请求 */
const submitting = ref(false)

/** 每辆车的未结工单（待修/维修中），用于开单时置灰并提示 */
const openOrderByKart = computed(() => {
  const map = new Map()
  for (const r of rows.value) {
    if (r.status !== 'DONE' && !map.has(r.kartId)) map.set(r.kartId, r)
  }
  return map
})
const openKartIds = computed(() => new Set(openOrderByKart.value.keys()))

function openOrderText(kartId) {
  const order = openOrderByKart.value.get(kartId)
  return order ? order.orderNo + ' ' + order.statusName : ''
}

async function load() {
  rows.value = await maintenanceApi.list()
  summary.value = await maintenanceApi.summary()
  karts.value = await kartApi.list()
}

/** 操作后统一刷新：维修单列表 + 费用统计 + 车辆状态 + 顶栏计数 */
async function refresh() {
  await load()
  await track.refreshOverview()
}

/** 失败时（如他人已先一步开工/完工）提示由拦截器弹出，这里静默刷新让页面与后端状态对齐 */
async function quietRefresh() {
  await refresh().catch(() => {})
}

async function start(row) {
  if (actingId.value !== null) return
  actingId.value = row.id
  try {
    await maintenanceApi.start(row.id, row.worker || '')
    await refresh()
  } catch (e) {
    await quietRefresh()
  } finally {
    actingId.value = null
  }
}

async function complete(row) {
  if (actingId.value !== null) return
  actingId.value = row.id
  try {
    await maintenanceApi.complete(row.id)
    await refresh()
  } catch (e) {
    await quietRefresh()
  } finally {
    actingId.value = null
  }
}

async function submit() {
  if (submitting.value) return
  submitting.value = true
  try {
    await maintenanceApi.create(form.value)
    dialog.value = false
    await refresh()
  } catch (e) {
    // 开单被拒（如该车已有未结工单）：对话框保持打开，仅刷新车辆未结单标注
    await quietRefresh()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>
