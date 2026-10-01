<template>
  <div>
    <div class="sum-grid" style="margin-bottom: 14px">
      <div class="sum-card"><div class="label">车辆总数</div><div class="value">{{ rows.length }}</div></div>
      <div class="sum-card"><div class="label">可用</div><div class="value">{{ count('AVAILABLE') }}</div></div>
      <div class="sum-card accent"><div class="label">在赛</div><div class="value">{{ count('ON_TRACK') }}</div></div>
      <div class="sum-card"><div class="label">维修</div><div class="value">{{ count('MAINTENANCE') }}</div></div>
      <div class="sum-card"><div class="label">停用</div><div class="value">{{ count('DISABLED') }}</div></div>
      <div class="sum-card accent"><div class="label">保养到期</div><div class="value">{{ rows.filter((r) => r.serviceDue).length }}</div></div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">车辆卡片墙</span>
        <span class="panel-extra">状态切换 可用 ↔ 维修 ↔ 停用 · 在赛由派车/收车产生</span>
        <el-button type="danger" size="small" style="margin-left: auto" @click="dialog = true">＋ 新增车辆</el-button>
      </div>

      <div class="kart-grid">
        <div v-for="k in rows" :key="k.id" class="kart-card" :class="cardClass(k.status)">
          <div style="display: flex; align-items: center; gap: 8px">
            <span class="kart-no">{{ k.kartNo }}</span>
            <span class="tag" :class="statusClass(k.status)">{{ k.statusName }}</span>
            <span v-if="k.serviceDue" class="tag warn">保养到期</span>
          </div>
          <div class="kart-sub">{{ k.modelName }} · {{ k.engineCc }}cc · {{ k.maxPower }}kW</div>
          <div class="kart-metrics">
            <div>累计里程<b>{{ k.totalKm }} km</b></div>
            <div>运行小时<b>{{ k.totalHours }} h</b></div>
            <div>保养间隔<b>{{ k.serviceIntervalH }} h</b></div>
          </div>
          <div class="kart-sub">上次保养：{{ k.lastServiceDate || '未保养' }} · {{ k.serviceDueText }}</div>
          <div class="kart-actions">
            <el-button v-if="k.status === 'AVAILABLE'" size="small" type="danger" :disabled="acting" @click="dispatch(k)">派车到本场</el-button>
            <el-button v-if="k.status === 'ON_TRACK'" size="small" type="warning" :disabled="acting" @click="recall(k)">收车</el-button>
            <el-button v-if="k.status === 'AVAILABLE'" size="small" :disabled="acting" @click="setStatus(k, 'MAINTENANCE')">转维修</el-button>
            <el-button v-if="k.status === 'MAINTENANCE'" size="small" :disabled="acting" @click="setStatus(k, 'AVAILABLE')">转可用</el-button>
            <el-button v-if="k.status === 'AVAILABLE'" size="small" :disabled="acting" @click="setStatus(k, 'DISABLED')">停用</el-button>
            <el-button v-if="k.status === 'DISABLED'" size="small" :disabled="acting" @click="setStatus(k, 'AVAILABLE')">启用</el-button>
          </div>
          <div v-if="k.sessionNo" class="kart-sub">当前场次：{{ k.sessionNo }}</div>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新增车辆" width="420">
      <el-form label-width="90px">
        <el-form-item label="车号"><el-input v-model="form.kartNo" placeholder="例如 K-13" /></el-form-item>
        <el-form-item label="车型">
          <el-select v-model="form.model" style="width: 100%">
            <el-option label="娱乐车" value="FUN" />
            <el-option label="竞技车（需中级以上驾照）" value="RACE" />
            <el-option label="儿童车（仅儿童组）" value="KID" />
            <el-option label="双人车（需初级以上驾照）" value="DOUBLE" />
          </el-select>
        </el-form-item>
        <el-form-item label="排量"><el-input v-model.number="form.engineCc" /></el-form-item>
        <el-form-item label="最大功率"><el-input v-model.number="form.maxPower" /></el-form-item>
        <el-form-item label="保养间隔"><el-input v-model.number="form.serviceIntervalH" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="danger" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { kartApi } from '../api'

const track = inject('track')
const rows = ref([])
const dialog = ref(false)
const form = ref({ kartNo: '', model: 'FUN', engineCc: 270, maxPower: 13.5, serviceIntervalH: 50 })
/** 有请求在途时禁用所有状态按钮，防止连点产生并发写 */
const acting = ref(false)

async function load() {
  rows.value = await kartApi.list()
}

function count(status) {
  return rows.value.filter((r) => r.status === status).length
}

function cardClass(status) {
  if (status === 'ON_TRACK') return 'is-on-track'
  if (status === 'MAINTENANCE') return 'is-maintenance'
  if (status === 'DISABLED') return 'is-disabled'
  return ''
}

function statusClass(status) {
  if (status === 'ON_TRACK') return 'danger'
  if (status === 'AVAILABLE') return 'ok'
  if (status === 'MAINTENANCE') return 'warn'
  return 'mute'
}

/** 包一层在途标记；失败（如该车还有未结维修单）时提示由拦截器弹出，并刷新对齐后端状态 */
async function run(fn) {
  if (acting.value) return
  acting.value = true
  try {
    await fn()
    await load()
    await track.refreshOverview()
  } catch (e) {
    await load().catch(() => {})
    await track.refreshOverview().catch(() => {})
  } finally {
    acting.value = false
  }
}

async function setStatus(kart, status) {
  await run(() => kartApi.setStatus(kart.id, status))
}

async function dispatch(kart) {
  await run(() => kartApi.dispatch(kart.id, track.sessionId.value))
}

async function recall(kart) {
  await run(() => kartApi.recall(kart.id))
}

async function submit() {
  await run(async () => {
    await kartApi.create(form.value)
    dialog.value = false
  })
}

watch(() => track.sessionId.value, () => load())
onMounted(load)
</script>
