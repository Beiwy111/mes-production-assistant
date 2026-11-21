<template>
  <div class="isc-scene">
    <canvas ref="canvasRef" class="isc-scene__canvas" />

    <div class="isc-alarm-hud" :class="{ 'isc-alarm-hud--active': activeAlarms.length }">
      <button class="isc-alarm-hud__trigger" @click.stop="alarmPanelOpen = !alarmPanelOpen">
        <span class="isc-alarm-hud__lamp" />
        <span><strong>安灯报警</strong><small>{{ activeAlarms.length ? `未关闭 ${activeAlarms.length} 条` : '当前无未关闭报警' }}</small></span>
        <b>{{ activeAlarms.length }}</b>
      </button>
      <div v-if="alarmPanelOpen" class="isc-alarm-hud__panel" @click.stop>
        <header><strong>{{ activeAlarms.length ? '当前报警' : '最近已关闭' }}</strong><span>{{ activeAlarms.length ? '实时定位' : '报警记录' }}</span></header>
        <button
          v-for="alarm in alarmPanelItems"
          :key="alarm.alarmId"
          class="isc-alarm-row"
          @click="focusAlarm(alarm)"
        >
          <i :class="`is-${String(alarm.alarmLevel || 'GENERAL').toLowerCase()}`" />
          <span><strong>{{ alarm.equipmentName || alarm.equipmentCode }}</strong><small>{{ alarm.alarmDescription || '未填写报警描述' }}</small></span>
          <time>{{ formatAlarmTime(alarm.reportedAt) }}</time>
        </button>
        <p v-if="!alarmPanelItems.length" class="isc-alarm-empty">暂无安灯报警记录</p>
      </div>
    </div>

    <!-- 悬浮设备标签 -->
    <div class="isc-scene__labels">
      <div
        v-for="item in labelItems"
        :key="item.id"
        class="isc-eq-tag"
        :class="[
          `isc-eq-tag--${item.level}`,
          {
            'isc-eq-tag--active': selectedId === item.id,
            'isc-eq-tag--alarm': item.activeAlarmCount > 0
          }
        ]"
        :style="{ left: item.x + 'px', top: item.y + 'px', opacity: item.visible ? 1 : 0 }"
        @click.stop="selectEq(item.id)"
      >
        <span class="isc-eq-tag__dot" :style="{ background: item.levelColor }" />
        <span class="isc-eq-tag__name">{{ item.name }}</span>
        <span v-if="item.activeAlarmCount" class="isc-eq-tag__alarm">{{ item.activeAlarmCount }}</span>
        <span class="isc-eq-tag__score" :style="{ color: item.levelColor }">{{ item.score }}</span>
      </div>
    </div>

    <!-- 设备详情固定在场景右侧，避免遮挡设备和其他标签 -->
    <Transition name="isc-detail">
      <aside v-if="selectedEq" class="isc-detail" @click.stop>
        <header class="isc-detail__head">
          <div>
            <span class="isc-detail__eyebrow">设备健康档案</span>
            <h3>{{ selectedEq.equipmentName || '未命名设备' }}</h3>
            <p>{{ selectedEq.equipmentCode || '—' }}</p>
          </div>
          <button class="isc-detail__close" aria-label="关闭设备详情" @click="closeDetail">×</button>
        </header>

        <div class="isc-detail__location">
          <span>{{ selectedEq.stageName || '未关联工序' }}</span>
          <i />
          <span>{{ selectedEq.workshopName || '未关联车间' }}</span>
        </div>

        <section class="isc-detail__status">
          <div>
            <span>健康评分</span>
            <strong :style="{ color: HEALTH_COLOR[selectedEq.healthLevel] || '#3dd68c' }">
              {{ selectedEq.healthScore ?? selectedEq.score ?? '—' }}
            </strong>
          </div>
        </section>

        <section class="isc-detail__grid">
          <div><span>累计运行</span><strong>{{ selectedEq.runHours ?? '—' }}<small>h</small></strong></div>
          <div><span>近7日报警</span><strong :class="{ 'is-danger': Number(selectedEq.alarm7d) > 0 }">{{ selectedEq.alarm7d ?? 0 }}</strong></div>
          <div><span>距上次保养</span><strong>{{ selectedEq.daysSinceMaint ?? '—' }}<small>天</small></strong></div>
          <div><span>近30日维修</span><strong>{{ selectedEq.faultCount30d ?? 0 }}</strong></div>
        </section>

        <section class="isc-detail__alarms" :class="{ 'has-alarm': selectedEq.activeAlarmCount > 0 }">
          <header><span>当前安灯报警</span><strong>{{ selectedEq.activeAlarmCount || 0 }}</strong></header>
          <div v-if="selectedEq.activeAlarms?.length" class="isc-detail__alarm-list">
            <article v-for="alarm in selectedEq.activeAlarms" :key="alarm.alarmId">
              <div><b>{{ alarm.alarmLevelCn || alarm.alarmLevel }}</b><time>{{ formatAlarmTime(alarm.reportedAt) }}</time></div>
              <p>{{ alarm.alarmDescription || '未填写报警描述' }}</p>
              <small>{{ alarm.alarmStatusCn || alarm.alarmStatus }}</small>
            </article>
          </div>
          <p v-else class="isc-detail__alarm-ok">当前无未关闭安灯报警</p>
        </section>

        <section class="isc-health-basis">
          <div class="isc-health-basis__head">
            <span>健康评分依据</span>
            <strong>基础分 100</strong>
          </div>
          <div class="isc-health-basis__rows">
            <div>
              <span>运行时长 <small>{{ selectedEq.runHours ?? '—' }}h · 估算</small></span>
              <strong>-{{ selectedEq.deductRun ?? 0 }}</strong>
            </div>
            <div>
              <span>近7日报警 <small>{{ selectedEq.alarm7d ?? 0 }}次</small></span>
              <strong>-{{ selectedEq.deductAlarm ?? 0 }}</strong>
            </div>
            <div>
              <span>维保间隔 <small>{{ selectedEq.daysSinceMaint ?? '—' }}天</small></span>
              <strong>-{{ selectedEq.deductMaint ?? 0 }}</strong>
            </div>
            <div>
              <span>近30日维修 <small>{{ selectedEq.faultCount30d ?? 0 }}次</small></span>
              <strong>-{{ selectedEq.deductNc ?? 0 }}</strong>
            </div>
          </div>
          <div class="isc-health-basis__result">
            <span>
              100 - {{ selectedEq.deductRun ?? 0 }} - {{ selectedEq.deductAlarm ?? 0 }}
              - {{ selectedEq.deductMaint ?? 0 }} - {{ selectedEq.deductNc ?? 0 }}
            </span>
            <strong>= {{ selectedEq.healthScore ?? selectedEq.score ?? '—' }}</strong>
          </div>
          <details class="isc-health-rules">
            <summary>查看扣分规则</summary>
            <p>运行时长：超过500/800小时扣10/20分</p>
            <p>7日报警：达到3/6/10次扣10/20/30分</p>
            <p>维保间隔：超过30/60/90天扣10/18/25分</p>
            <p>30日维修：达到2/5/10次扣5/15/25分</p>
          </details>
        </section>

        <section v-if="selectedEq.advice" class="isc-detail__advice">
          <span>维护建议</span>
          <p>{{ selectedEq.advice }}</p>
        </section>
      </aside>
    </Transition>

    <!-- 场景内工具条 -->
    <div class="isc-scene__toolbar">
      <button class="isc-tbtn" @click="resetCamera">复位</button>
      <button class="isc-tbtn" :class="{ 'isc-tbtn--on': autoRotate }" @click="autoRotate = !autoRotate">
        {{ autoRotate ? '停止旋转' : '自动旋转' }}
      </button>
      <span class="isc-tbtn isc-tbtn--hint">拖转 · 滚轮缩放 · 右键平移 · 点击设备查看信息</span>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import {
  createEquipmentMesh,
  createConveyorMesh,
  createBufferRack,
  createAgvMesh,
  createWorkstationPad,
  HEALTH_COLOR
} from './EquipmentMesh.js'
import { fetchAlarmViews, fetchEquipmentHealth } from '@/api/business'
import {
  buildEquipmentPositions,
  buildStageZones,
  buildSceneInfrastructure,
  isSceneEquipment
} from '@/utils/factorySceneLayout'

const emit = defineEmits(['select', 'deselect'])

const canvasRef = ref(null)
const autoRotate = ref(true)
const labelItems = ref([])
const selectedEq = ref(null)
const selectedId = ref(null)
const equipList = ref([])
const alarmList = ref([])
const alarmPanelOpen = ref(false)

const activeAlarms = computed(() => alarmList.value.filter((alarm) => alarm.alarmStatus !== 'CLOSED'))
const alarmPanelItems = computed(() => {
  const source = activeAlarms.value.length ? activeAlarms.value : alarmList.value.filter((alarm) => alarm.alarmStatus === 'CLOSED')
  return source.slice(0, 5)
})

let renderer, scene, camera, controls, animId, timer = null
let introFrameId = null
let introPlayed = false
let introRunning = false
const meshMap = new Map()
let layoutMap = {}

function getPos(code) { return layoutMap[code] || null }
function rebuildLayout(list) { layoutMap = buildEquipmentPositions(list) }

function enrichEq(eq) {
  const pos = getPos(eq.equipmentCode)
  return {
    ...eq,
    stageName: pos?.stageName || eq.parentStepName || '',
    workshopName: pos?.workshopName || eq.workshop || ''
  }
}

function selectEq(id) {
  selectedId.value = id
  const eq = equipList.value.find((e) => e.equipmentId === id)
  selectedEq.value = eq ? enrichEq(eq) : null
  if (selectedEq.value) emit('select', selectedEq.value)
}

function formatAlarmTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(5, 16)
}

function focusAlarm(alarm) {
  if (!alarm?.equipmentId) return
  selectEq(alarm.equipmentId)
  const eq = equipList.value.find((item) => item.equipmentId === alarm.equipmentId)
  const pos = eq ? getPos(eq.equipmentCode) : null
  if (pos && camera && controls) {
    flyTo(new THREE.Vector3(pos.x, 7, pos.z + 9), new THREE.Vector3(pos.x, 0, pos.z))
  }
}

function closeDetail() {
  selectedId.value = null
  selectedEq.value = null
  emit('deselect')
}

function initScene() {
  const canvas = canvasRef.value
  if (!canvas) return
  const W = canvas.clientWidth || 900
  const H = canvas.clientHeight || 600

  renderer = new THREE.WebGLRenderer({ canvas, antialias: true })
  renderer.setSize(W, H, false)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setClearColor(0xf5f7fa, 1)
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.05

  scene = new THREE.Scene()
  scene.fog = new THREE.Fog(0xf5f7fa, 50, 95)

  camera = new THREE.PerspectiveCamera(44, W / H, 0.1, 200)
  camera.position.set(2, 10, 14)
  camera.lookAt(4, 0, 0)

  scene.add(new THREE.AmbientLight(0xffffff, 0.75))
  const key = new THREE.DirectionalLight(0xffffff, 1.15)
  key.position.set(12, 22, 14)
  key.castShadow = true
  key.shadow.mapSize.set(2048, 2048)
  key.shadow.camera.left = -30
  key.shadow.camera.right = 30
  key.shadow.camera.top = 20
  key.shadow.camera.bottom = -20
  scene.add(key)
  const rim = new THREE.DirectionalLight(0xd0e4f7, 0.4)
  rim.position.set(-10, 8, -8)
  scene.add(rim)

  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(62, 28),
    new THREE.MeshStandardMaterial({ color: 0xe8ecf0, roughness: 0.88, metalness: 0.06 })
  )
  floor.rotation.x = -Math.PI / 2
  floor.position.y = -0.52
  floor.receiveShadow = true
  scene.add(floor)

  const grid = new THREE.GridHelper(62, 62, 0xc8d6e8, 0xdce4ef)
  grid.position.y = -0.51
  scene.add(grid)

  buildIndustrialInfrastructure()
  buildStageZones().forEach((z) => buildZone(z))

  controls = new OrbitControls(camera, canvas)
  controls.enableDamping = true
  controls.dampingFactor = 0.06
  controls.minDistance = 5
  controls.maxDistance = 32
  controls.maxPolarAngle = Math.PI / 2.15
  controls.autoRotate = true
  controls.target.set(4, 0, 0)
  controls.update()

  canvas.addEventListener('click', onCanvasClick)
  animate()
}

function buildIndustrialInfrastructure() {
  const infra = buildSceneInfrastructure()

  infra.floorMarkings.forEach((m) => {
    const pts = [
      new THREE.Vector3(m.x1, 0.02, m.z1),
      new THREE.Vector3(m.x2, 0.02, m.z2)
    ]
    scene.add(new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(pts),
      new THREE.LineBasicMaterial({ color: m.color, linewidth: 2 })
    ))
  })

  infra.conveyors.forEach((c) => {
    const seg = createConveyorMesh(c.length, c.axis)
    seg.position.set(c.x, -0.46, c.z)
    scene.add(seg)
  })

  infra.buffers.forEach((b) => {
    const rack = createBufferRack()
    rack.position.set(b.x, -0.46, b.z)
    scene.add(rack)
  })

  infra.workstations.forEach((ws) => {
    const pad = createWorkstationPad(ws.w, ws.d)
    pad.position.set(ws.x, 0, ws.z)
    scene.add(pad)
  })

  const agv = infra.agvZone
  const agvPad = new THREE.Mesh(
    new THREE.PlaneGeometry(agv.w, agv.d),
    new THREE.MeshBasicMaterial({ color: 0xe8f5e9, transparent: true, opacity: 0.85 })
  )
  agvPad.rotation.x = -Math.PI / 2
  agvPad.position.set(agv.x, -0.49, agv.z)
  scene.add(agvPad)
  for (let i = 0; i < 3; i++) {
    const v = createAgvMesh(i === 1 ? '#ff6f00' : '#ff8f00')
    v.position.set(agv.x - 1.2 + i * 1.2, 0, agv.z)
    scene.add(v)
  }
  addZoneLabel(agv.x, agv.z + agv.d / 2 + 0.75, agv.label, 0xff8f00)
}

function buildZone(zone) {
  const c = new THREE.Color(zone.color)
  const hw = zone.halfW
  const hd = zone.halfD
  const pts = [[-hw, -hd], [hw, -hd], [hw, hd], [-hw, hd], [-hw, -hd]]
    .map(([x, z]) => new THREE.Vector3(zone.x + x, 0.03, zone.z + z))
  scene.add(new THREE.Line(
    new THREE.BufferGeometry().setFromPoints(pts),
    new THREE.LineBasicMaterial({ color: c, transparent: true, opacity: 0.65 })
  ))
  const fill = new THREE.Mesh(
    new THREE.PlaneGeometry(hw * 2, hd * 2),
    new THREE.MeshBasicMaterial({ color: c, transparent: true, opacity: 0.07, side: THREE.DoubleSide })
  )
  fill.rotation.x = -Math.PI / 2
  fill.position.set(zone.x, -0.495, zone.z)
  scene.add(fill)
  addZoneLabel(zone.x, zone.z + hd + 0.75, `${zone.order}. ${zone.name}`, zone.color)
}

function addZoneLabel(x, z, text, colorHex) {
  const cv = document.createElement('canvas')
  cv.width = 1024
  cv.height = 180
  const ctx = cv.getContext('2d')
  const hex = new THREE.Color(colorHex).getHexString()

  ctx.shadowColor = 'rgba(15, 39, 71, 0.22)'
  ctx.shadowBlur = 20
  ctx.shadowOffsetY = 8
  ctx.fillStyle = 'rgba(255, 255, 255, 0.98)'
  ctx.beginPath()
  ctx.roundRect(18, 18, 988, 144, 18)
  ctx.fill()

  ctx.shadowColor = 'transparent'
  ctx.strokeStyle = `#${hex}`
  ctx.lineWidth = 8
  ctx.beginPath()
  ctx.roundRect(18, 18, 988, 144, 18)
  ctx.stroke()
  ctx.fillStyle = `#${hex}`
  ctx.fillRect(18, 18, 18, 144)

  ctx.fillStyle = '#172b4d'
  ctx.font = '700 56px "Microsoft YaHei", sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, 520, 90)

  const texture = new THREE.CanvasTexture(cv)
  texture.colorSpace = THREE.SRGBColorSpace
  texture.minFilter = THREE.LinearFilter
  texture.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy())
  const boardMaterial = new THREE.MeshBasicMaterial({
    map: texture,
    transparent: true,
    side: THREE.DoubleSide,
    depthWrite: false,
    polygonOffset: true,
    polygonOffsetFactor: -2,
    polygonOffsetUnits: -2
  })
  const board = new THREE.Mesh(
    new THREE.PlaneGeometry(5.2, 0.92),
    boardMaterial
  )
  board.position.set(x, -0.45, z)
  board.rotation.x = -Math.PI / 2
  board.renderOrder = 4
  scene.add(board)
}

function placeEquipments(list) {
  const sceneEqs = (list || []).filter(isSceneEquipment)
  rebuildLayout(sceneEqs)
  const codes = new Set(sceneEqs.map((e) => e.equipmentCode))

  meshMap.forEach((entry, code) => {
    if (!codes.has(code)) {
      scene.remove(entry.group)
      meshMap.delete(code)
    }
  })

  sceneEqs.forEach((eq) => {
    const pos = getPos(eq.equipmentCode)
    if (!pos) return
    if (meshMap.has(eq.equipmentCode)) {
      const entry = meshMap.get(eq.equipmentCode)
      entry.updateVisual(eq.healthLevel, eq.activeAlarmCount > 0)
      entry.group.position.set(pos.x, 0, pos.z)
      entry.group.rotation.y = pos.rotY ?? 0
      return
    }
    const pad = createWorkstationPad(2.1, 1.7)
    pad.position.set(pos.x, 0, pos.z)
    scene.add(pad)

    const { group, updateVisual } = createEquipmentMesh(eq)
    group.position.set(pos.x, 0, pos.z)
    group.rotation.y = pos.rotY ?? 0
    group.scale.setScalar(1.75)
    group.userData = { equipmentId: eq.equipmentId, equipmentCode: eq.equipmentCode }
    group.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true } })
    scene.add(group)
    meshMap.set(eq.equipmentCode, { group, updateVisual })
  })

  if (selectedId.value) {
    const hit = sceneEqs.find((e) => e.equipmentId === selectedId.value)
    if (hit) selectedEq.value = enrichEq(hit)
  }

  if (!introPlayed && sceneEqs.length) {
    introPlayed = true
    introFrameId = requestAnimationFrame(playCinematicIntro)
  }
}

const raycaster = new THREE.Raycaster()
const mouse = new THREE.Vector2()

function onCanvasClick(e) {
  if (introRunning) return
  const canvas = canvasRef.value
  if (!canvas) return
  const r = canvas.getBoundingClientRect()
  mouse.x = ((e.clientX - r.left) / r.width) * 2 - 1
  mouse.y = -((e.clientY - r.top) / r.height) * 2 + 1
  raycaster.setFromCamera(mouse, camera)
  const targets = []
  meshMap.forEach(({ group }) => group.traverse((o) => { if (o.isMesh) targets.push(o) }))
  const hits = raycaster.intersectObjects(targets, false)
  if (!hits.length) {
    closeDetail()
    return
  }
  let obj = hits[0].object
  while (obj && !obj.userData?.equipmentCode) obj = obj.parent
  if (!obj?.userData?.equipmentId) return
  selectEq(obj.userData.equipmentId)
  const pos = getPos(obj.userData.equipmentCode)
  if (pos) {
    flyTo(new THREE.Vector3(pos.x, 7, pos.z + 9), new THREE.Vector3(pos.x, 0, pos.z))
  }
}

function stopCinematicIntro() {
  if (introFrameId) cancelAnimationFrame(introFrameId)
  introFrameId = null
  introRunning = false
  if (controls) controls.enabled = true
}

function playCinematicIntro() {
  if (!camera || !controls) return
  const zones = buildStageZones().slice().sort((a, b) => a.x - b.x)
  if (!zones.length) return

  introRunning = true
  controls.enabled = false
  controls.autoRotate = false

  const firstX = zones[0].x
  const lastX = zones[zones.length - 1].x
  const startCam = new THREE.Vector3(firstX - 2.5, 5.2, 8.8)
  const endCam = new THREE.Vector3(lastX + 1.8, 5.8, 8.8)
  const overviewCam = new THREE.Vector3(2, 10, 14)
  const startLook = new THREE.Vector3(firstX + 2.2, 0, 0)
  const endLook = new THREE.Vector3(lastX - 1.5, 0, 0)
  const overviewLook = new THREE.Vector3(4, 0, 0)
  const sweepDuration = 1750
  const settleDuration = 850
  const startedAt = performance.now()

  camera.position.copy(startCam)
  controls.target.copy(startLook)
  controls.update()

  const ease = (p) => p * p * (3 - 2 * p)
  const tick = (now) => {
    if (!introRunning || !camera || !controls) return
    const elapsed = now - startedAt

    if (elapsed < sweepDuration) {
      const p = ease(Math.min(elapsed / sweepDuration, 1))
      camera.position.lerpVectors(startCam, endCam, p)
      camera.position.y += Math.sin(p * Math.PI) * 1.15
      controls.target.lerpVectors(startLook, endLook, p)
    } else {
      const p = ease(Math.min((elapsed - sweepDuration) / settleDuration, 1))
      camera.position.lerpVectors(endCam, overviewCam, p)
      controls.target.lerpVectors(endLook, overviewLook, p)
      if (p >= 1) {
        stopCinematicIntro()
        camera.position.copy(overviewCam)
        controls.target.copy(overviewLook)
        controls.autoRotate = autoRotate.value
        controls.update()
        return
      }
    }

    controls.update()
    introFrameId = requestAnimationFrame(tick)
  }

  introFrameId = requestAnimationFrame(tick)
}

function flyTo(camTarget, lookTarget) {
  const camStart = camera.position.clone()
  const lookStart = controls.target.clone()
  const t0 = performance.now()
  const dur = 650
  const tick = () => {
    const p = Math.min((performance.now() - t0) / dur, 1)
    const e = p < 0.5 ? 2 * p * p : -1 + (4 - 2 * p) * p
    camera.position.lerpVectors(camStart, camTarget, e)
    controls.target.lerpVectors(lookStart, lookTarget, e)
    controls.update()
    if (p < 1) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}

function updateLabels() {
  const canvas = canvasRef.value
  if (!canvas || !camera) return
  const W = canvas.clientWidth
  const H = canvas.clientHeight
  labelItems.value = equipList.value.filter(isSceneEquipment).map((eq, idx) => {
    const pos = getPos(eq.equipmentCode)
    if (!pos) return null
    const enriched = enrichEq(eq)
    const anchorY = 3.2 + (idx % 2) * 0.7
    const v = new THREE.Vector3(pos.x, anchorY, pos.z).project(camera)
    const x = (v.x * 0.5 + 0.5) * W
    const y = (-v.y * 0.5 + 0.5) * H
    const cardW = 70
    const cardH = 28
    return {
      id: eq.equipmentId,
      name: eq.equipmentName || eq.equipmentCode || '未命名设备',
      stageName: enriched.stageName,
      workshopName: enriched.workshopName,
      score: eq.healthScore ?? '—',
      runHours: eq.runHours,
      alarm7d: eq.alarm7d,
      daysSinceMaint: eq.daysSinceMaint,
      faultCount30d: eq.faultCount30d,
      advice: eq.advice,
      level: (eq.healthLevel ?? 'GOOD').toLowerCase(),
      levelColor: HEALTH_COLOR[eq.healthLevel] ?? '#3dd68c',
      activeAlarmCount: eq.activeAlarmCount || 0,
      x: x - cardW,
      y: y - cardH,
      visible: v.z < 1 && x > 60 && x < W - 60 && y > 30 && y < H - 30
    }
  }).filter(Boolean)
}

function animate() {
  animId = requestAnimationFrame(animate)
  controls.autoRotate = autoRotate.value && !introRunning
  controls.autoRotateSpeed = 0.35
  controls.update()
  renderer.render(scene, camera)
  updateLabels()
}

function resetCamera() {
  stopCinematicIntro()
  camera.position.set(2, 10, 14)
  controls.target.set(4, 0, 0)
  controls.update()
}

let resizeObserver = null

async function loadData() {
  try {
    const [healthRes, alarmRes] = await Promise.all([
      fetchEquipmentHealth(),
      fetchAlarmViews({ silent: true })
    ])
    const list = Array.isArray(healthRes) ? healthRes : (healthRes.data ?? [])
    const alarms = Array.isArray(alarmRes) ? alarmRes : (alarmRes.data ?? [])
    syncEquipments(list, alarms)
  } catch { /* ignore */ }
}

function onResize() {
  const c = canvasRef.value
  if (!c || !renderer || !camera) return
  const W = c.clientWidth
  const H = c.clientHeight
  renderer.setSize(W, H, false)
  camera.aspect = W / H
  camera.updateProjectionMatrix()
}

onMounted(async () => {
  await new Promise((r) => setTimeout(r, 30))
  initScene()
  await loadData()
  timer = setInterval(loadData, 30000)
  window.addEventListener('resize', onResize)
  if (canvasRef.value?.parentElement) {
    resizeObserver = new ResizeObserver(onResize)
    resizeObserver.observe(canvasRef.value.parentElement)
  }
})

onUnmounted(() => {
  cancelAnimationFrame(animId)
  stopCinematicIntro()
  canvasRef.value?.removeEventListener('click', onCanvasClick)
  controls?.dispose()
  renderer?.dispose()
  scene?.clear()
  meshMap.clear()
  clearInterval(timer)
  window.removeEventListener('resize', onResize)
  resizeObserver?.disconnect()
})

watch(autoRotate, (v) => { if (controls) controls.autoRotate = v })

function syncEquipments(list, alarms = []) {
  if (!Array.isArray(list) || !list.length) return
  alarmList.value = Array.isArray(alarms)
    ? alarms.slice().sort((a, b) => String(b.reportedAt || '').localeCompare(String(a.reportedAt || '')))
    : []
  const merged = list.map((eq) => {
    const equipmentAlarms = alarmList.value.filter((alarm) => alarm.equipmentId === eq.equipmentId)
    const active = equipmentAlarms.filter((alarm) => alarm.alarmStatus !== 'CLOSED')
    return {
      ...eq,
      activeAlarms: active,
      activeAlarmCount: active.length,
      urgentAlarmCount: active.filter((alarm) => alarm.alarmLevel === 'URGENT').length
    }
  })
  equipList.value = merged
  if (scene) placeEquipments(merged)
}

defineExpose({ syncEquipments })
</script>

<style scoped>
.isc-scene {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: #f5f7fa;
  overflow: hidden;
}

.isc-scene__canvas {
  width: 100%;
  height: 100%;
  display: block;
}

.isc-scene__labels {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.isc-eq-tag {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  background: #fff;
  border: 1px solid #d8dee8;
  font-size: 10px;
  font-family: Consolas, 'Courier New', monospace;
  color: #4a5568;
  pointer-events: all;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-shadow: 0 1px 4px rgba(0, 27, 63, 0.08);
}

.isc-eq-tag:hover,
.isc-eq-tag--active {
  border-color: #3b82f6;
  background: #f0f7ff;
  box-shadow: 0 2px 10px rgba(59, 130, 246, 0.2);
}

.isc-eq-tag--danger,
.isc-eq-tag--alert { border-color: #f56c6c; background: #fff5f5; }
.isc-eq-tag--warn { border-color: #e6a23c; background: #fffbf0; }

.isc-eq-tag__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.isc-eq-tag__name { font-weight: 700; color: #001b3f; }
.isc-eq-tag__score { font-weight: 700; margin-left: 2px; }

.isc-detail {
  position: absolute;
  top: 14px;
  right: 14px;
  bottom: 52px;
  z-index: 8;
  width: min(380px, calc(100% - 56px));
  padding: 22px;
  overflow: auto;
  color: #334155;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(203, 213, 225, 0.9);
  border-radius: 12px;
  box-shadow: 0 14px 40px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(14px);
}

.isc-detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.isc-detail__eyebrow {
  color: #3b82f6;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1.6px;
}

.isc-detail__head h3 {
  margin: 5px 0 2px;
  color: #0f2747;
  font-size: 22px;
  line-height: 1.35;
}

.isc-detail__head p {
  margin: 0;
  color: #94a3b8;
  font-family: Consolas, monospace;
  font-size: 13px;
}

.isc-detail__close {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  background: #f8fafc;
  color: #64748b;
  cursor: pointer;
  font-size: 20px;
  line-height: 1;
  padding: 0;
}

.isc-detail__close:hover {
  border-color: #93c5fd;
  color: #2563eb;
  background: #eff6ff;
}

.isc-detail__location {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0 12px;
  padding: 13px 15px;
  border-radius: 8px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 13px;
}

.isc-detail__location i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #94a3b8;
}

.isc-detail__status {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.isc-detail__status > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
  border: 1px solid #e8edf4;
  border-radius: 8px;
  background: #fff;
}

.isc-detail__status span,
.isc-detail__grid span,
.isc-detail__advice > span {
  color: #94a3b8;
  font-size: 12px;
}

.isc-detail__status strong {
  font-size: 24px;
  font-weight: 700;
}

.isc-detail__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 12px;
}

.isc-detail__grid > div {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 15px;
  border: 1px solid #e8edf4;
  border-radius: 8px;
}

.isc-detail__grid strong {
  color: #0f2747;
  font-size: 21px;
}

.isc-detail__grid small {
  margin-left: 2px;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 400;
}

.isc-detail__grid .is-danger { color: #ef4444; }

.isc-health-basis {
  margin-top: 12px;
  padding: 15px;
  border: 1px solid #dce6f2;
  border-radius: 8px;
  background: #f8fbff;
}

.isc-health-basis__head,
.isc-health-basis__rows > div,
.isc-health-basis__result {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.isc-health-basis__head {
  padding-bottom: 9px;
  border-bottom: 1px solid #e5edf6;
}

.isc-health-basis__head > span {
  color: #0f2747;
  font-size: 14px;
  font-weight: 700;
}

.isc-health-basis__head > strong {
  color: #2563eb;
  font-size: 13px;
}

.isc-health-basis__rows {
  display: grid;
  gap: 10px;
  padding: 12px 0;
}

.isc-health-basis__rows span {
  color: #64748b;
  font-size: 12px;
}

.isc-health-basis__rows small {
  margin-left: 4px;
  color: #94a3b8;
  font-size: 11px;
}

.isc-health-basis__rows strong {
  color: #dc2626;
  font-size: 13px;
}

.isc-health-basis__result {
  padding-top: 9px;
  border-top: 1px solid #e5edf6;
}

.isc-health-basis__result span {
  color: #64748b;
  font-family: Consolas, monospace;
  font-size: 11px;
}

.isc-health-basis__result strong {
  color: #0f2747;
  font-size: 17px;
  white-space: nowrap;
}

.isc-health-rules {
  margin-top: 9px;
  color: #64748b;
  font-size: 11px;
}

.isc-health-rules summary {
  color: #3b82f6;
  cursor: pointer;
  user-select: none;
}

.isc-health-rules p {
  margin: 5px 0 0;
  line-height: 1.35;
}

.isc-detail__advice {
  margin-top: 12px;
  padding: 15px;
  border-left: 3px solid #f59e0b;
  border-radius: 4px 8px 8px 4px;
  background: #fffbeb;
}

.isc-detail__advice p {
  margin: 6px 0 0;
  color: #475569;
  font-size: 13px;
  line-height: 1.4;
}

.isc-detail-enter-active,
.isc-detail-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.isc-detail-enter-from,
.isc-detail-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

.isc-scene__toolbar {
  position: absolute;
  bottom: 10px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  z-index: 2;
}

.isc-tbtn {
  padding: 4px 12px;
  background: #fff;
  border: 1px solid #d8dee8;
  color: #4a5568;
  font-size: 11px;
  cursor: pointer;
}

.isc-tbtn:hover,
.isc-tbtn--on {
  border-color: #3b82f6;
  color: #1d4ed8;
  background: #f0f7ff;
}

.isc-tbtn--hint {
  border: none;
  background: transparent;
  color: #909399;
  cursor: default;
  padding-left: 4px;
}
.isc-alarm-hud {
  position: absolute;
  top: 14px;
  left: 14px;
  z-index: 9;
  width: 292px;
}

.isc-alarm-hud__trigger {
  display: grid;
  grid-template-columns: 12px 1fr auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #dbe4ec;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  color: #334155;
  text-align: left;
  cursor: pointer;
}

.isc-alarm-hud__trigger > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; }
.isc-alarm-hud__trigger strong { color: #17324d; font-size: 12px; }
.isc-alarm-hud__trigger small { color: #7b8b9b; font-size: 10px; }
.isc-alarm-hud__trigger b { min-width: 24px; color: #64748b; font-size: 18px; text-align: center; }

.isc-alarm-hud__lamp {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #52b788;
  box-shadow: 0 0 0 4px rgba(82, 183, 136, 0.13);
}

.isc-alarm-hud--active .isc-alarm-hud__trigger { border-color: rgba(239, 68, 68, 0.55); }
.isc-alarm-hud--active .isc-alarm-hud__trigger b { color: #dc2626; }
.isc-alarm-hud--active .isc-alarm-hud__lamp {
  background: #ef4444;
  box-shadow: 0 0 0 4px rgba(239, 68, 68, 0.16);
  animation: isc-alarm-pulse 1.15s ease-out infinite;
}

.isc-alarm-hud__panel {
  margin-top: 7px;
  padding: 10px;
  border: 1px solid #dbe4ec;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.16);
}

.isc-alarm-hud__panel > header {
  display: flex;
  justify-content: space-between;
  padding: 1px 2px 8px;
  color: #243b53;
  font-size: 11px;
}
.isc-alarm-hud__panel > header span { color: #94a3b8; font-size: 9px; }

.isc-alarm-row {
  display: grid;
  grid-template-columns: 7px 1fr auto;
  align-items: start;
  gap: 8px;
  width: 100%;
  padding: 8px 4px;
  border: 0;
  border-top: 1px solid #edf2f7;
  background: transparent;
  color: #475569;
  text-align: left;
  cursor: pointer;
}
.isc-alarm-row:hover { background: #f8fafc; }
.isc-alarm-row i { width: 6px; height: 6px; margin-top: 4px; border-radius: 50%; background: #f59e0b; }
.isc-alarm-row i.is-urgent { background: #ef4444; }
.isc-alarm-row > span { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.isc-alarm-row strong { color: #1e3a56; font-size: 10px; }
.isc-alarm-row small { overflow: hidden; color: #64748b; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.isc-alarm-row time { color: #94a3b8; font-size: 8px; white-space: nowrap; }
.isc-alarm-empty { margin: 12px 0 6px; color: #94a3b8; font-size: 10px; text-align: center; }

.isc-eq-tag--alarm {
  border-color: #ef4444 !important;
  background: #fff7f7 !important;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.13), 0 4px 12px rgba(185, 28, 28, 0.2) !important;
  animation: isc-tag-alarm 1.2s ease-in-out infinite alternate;
}
.isc-eq-tag__alarm {
  min-width: 15px;
  height: 15px;
  border-radius: 8px;
  background: #ef4444;
  color: #fff;
  font: 700 9px/15px sans-serif;
  text-align: center;
}

.isc-detail__alarms {
  margin-top: 14px;
  padding: 15px;
  border: 1px solid #dfe8e5;
  border-radius: 9px;
  background: #f7fbf9;
}
.isc-detail__alarms.has-alarm { border-color: #fecaca; background: #fff7f7; }
.isc-detail__alarms > header { display: flex; align-items: center; justify-content: space-between; }
.isc-detail__alarms > header span { color: #526879; font-size: 12px; font-weight: 700; }
.isc-detail__alarms > header strong { color: #52a58a; font-size: 20px; }
.isc-detail__alarms.has-alarm > header strong { color: #dc2626; }
.isc-detail__alarm-list article { margin-top: 9px; padding-top: 9px; border-top: 1px solid #fee2e2; }
.isc-detail__alarm-list article > div { display: flex; justify-content: space-between; }
.isc-detail__alarm-list b { color: #dc2626; font-size: 11px; }
.isc-detail__alarm-list time, .isc-detail__alarm-list small { color: #94a3b8; font-size: 10px; }
.isc-detail__alarm-list p { margin: 6px 0; color: #475569; font-size: 12px; line-height: 1.5; }
.isc-detail__alarm-ok { margin: 10px 0 0; color: #659789; font-size: 12px; }

@keyframes isc-alarm-pulse {
  0% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.42); }
  100% { box-shadow: 0 0 0 9px rgba(239, 68, 68, 0); }
}
@keyframes isc-tag-alarm {
  from { transform: translateY(0); }
  to { transform: translateY(-2px); }
}
</style>
