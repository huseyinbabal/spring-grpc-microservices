<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useGrpc } from '~/composables/useGrpc'
import {
  CreateShipmentRequest,
  ListShipmentsRequest,
  ShipmentStatus,
} from '~/proto/cargo/shipment/v1/shipment_pb'
import { Address, Page } from '~/proto/cargo/common/v1/common_pb'
import {
  ReportLocationRequest,
  TrackingEvent,
  GetTrackingRequest,
} from '~/proto/cargo/tracking/v1/tracking_pb'

const { shipmentClient, trackingClient } = useGrpc()

const originCity = ref('Berlin')
const originCountry = ref('DE')
const destCity = ref('Paris')
const destCountry = ref('FR')
const carrier = ref('DHL')
const weightKg = ref(7.25)
const createResult = ref('')
const createError = ref('')
const shipments = ref<any[]>([])
const listError = ref('')
const locationShipmentId = ref('')
const lat = ref(52.52)
const lng = ref(13.4)
const locationResult = ref('')
const locationError = ref('')
const trackingShipmentId = ref('')
const trackingResult = ref('')
const trackingError = ref('')

const statusLabels: Record<number, string> = {
  [ShipmentStatus.UNSPECIFIED]: 'UNSPECIFIED',
  [ShipmentStatus.CREATED]: 'CREATED',
  [ShipmentStatus.IN_TRANSIT]: 'IN_TRANSIT',
  [ShipmentStatus.DELIVERED]: 'DELIVERED',
  [ShipmentStatus.CANCELLED]: 'CANCELLED',
}

async function createShipment() {
  createError.value = ''
  createResult.value = ''
  try {
    const resp = await shipmentClient.createShipment(new CreateShipmentRequest({
      origin: new Address({
        line1: 'Alexanderplatz 1',
        city: originCity.value,
        country: originCountry.value,
        postalCode: '10178',
      }),
      destination: new Address({
        line1: 'Rue de Rivoli 1',
        city: destCity.value,
        country: destCountry.value,
        postalCode: '75001',
      }),
      carrier: carrier.value,
      weightKg: weightKg.value,
    }))
    if (resp.shipment) {
      createResult.value = JSON.stringify({
        id: resp.shipment.id,
        trackingCode: resp.shipment.trackingCode,
        carrier: resp.shipment.carrier,
        status: statusLabels[resp.shipment.status] ?? resp.shipment.status,
      }, null, 2)
    }
    await loadShipments()
  } catch (e: any) {
    createError.value = e.message
  }
}

async function loadShipments() {
  listError.value = ''
  try {
    const resp = await shipmentClient.listShipments(new ListShipmentsRequest({
      page: new Page({ size: 20 }),
    }))
    shipments.value = resp.shipments.map((s) => ({
      id: s.id,
      trackingCode: s.trackingCode,
      carrier: s.carrier,
      status: statusLabels[s.status] ?? s.status,
    }))
  } catch (e: any) {
    listError.value = e.message
  }
}

async function reportLocation() {
  locationError.value = ''
  locationResult.value = ''
  try {
    const resp = await trackingClient.reportLocation(new ReportLocationRequest({
      event: new TrackingEvent({
        shipmentId: locationShipmentId.value,
        lat: lat.value,
        lng: lng.value,
        source: 'web-ui',
      }),
    }))
    locationResult.value = `Event ID: ${resp.id}`
  } catch (e: any) {
    locationError.value = e.message
  }
}

async function getTracking() {
  trackingError.value = ''
  trackingResult.value = ''
  try {
    const resp = await trackingClient.getTracking(new GetTrackingRequest({
      shipmentId: trackingShipmentId.value,
    }))
    trackingResult.value = JSON.stringify({
      shipmentId: resp.shipmentId,
      status: statusLabels[resp.status] ?? resp.status,
      lastLat: resp.lastLat,
      lastLng: resp.lastLng,
    }, null, 2)
  } catch (e: any) {
    trackingError.value = e.message
  }
}

onMounted(() => loadShipments())
</script>

<template>
  <div style="max-width: 900px; margin: 0 auto; padding: 20px; font-family: system-ui, sans-serif;">
    <h1 style="color: #1a73e8;">Cargo Tracking Platform</h1>
    <p style="color: #666;">gRPC-Web + ConnectRPC + Nuxt 3 + @bufbuild/protobuf</p>

    <section style="margin: 24px 0; padding: 16px; border: 1px solid #ddd; border-radius: 8px;">
      <h2>Create Shipment</h2>
      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px;">
        <label>Origin City <input v-model="originCity" style="width:100%; padding:6px;" /></label>
        <label>Origin Country <input v-model="originCountry" style="width:100%; padding:6px;" /></label>
        <label>Dest City <input v-model="destCity" style="width:100%; padding:6px;" /></label>
        <label>Dest Country <input v-model="destCountry" style="width:100%; padding:6px;" /></label>
        <label>Carrier <input v-model="carrier" style="width:100%; padding:6px;" /></label>
        <label>Weight (kg) <input v-model.number="weightKg" type="number" step="0.1" style="width:100%; padding:6px;" /></label>
      </div>
      <button @click="createShipment" style="padding: 8px 20px; background: #1a73e8; color: white; border: none; border-radius: 4px; cursor: pointer;">
        CreateShipment RPC
      </button>
      <pre v-if="createResult" style="margin-top: 12px; background: #f5f5f5; padding: 12px; border-radius: 4px; font-size: 13px;">{{ createResult }}</pre>
      <p v-if="createError" style="color: red;">{{ createError }}</p>
    </section>

    <section style="margin: 24px 0; padding: 16px; border: 1px solid #ddd; border-radius: 8px;">
      <h2>Shipments <button @click="loadShipments" style="font-size: 12px; margin-left: 8px;">Refresh</button></h2>
      <p v-if="listError" style="color: red;">{{ listError }}</p>
      <table v-if="shipments.length" style="width: 100%; border-collapse: collapse; font-size: 14px;">
        <thead>
          <tr style="background: #f0f0f0;">
            <th style="padding: 8px; text-align: left;">ID</th>
            <th style="padding: 8px; text-align: left;">Tracking Code</th>
            <th style="padding: 8px; text-align: left;">Carrier</th>
            <th style="padding: 8px; text-align: left;">Status</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in shipments" :key="s.id" style="border-top: 1px solid #eee;">
            <td style="padding: 8px; font-family: monospace; font-size: 12px;">{{ s.id.slice(0, 8) }}...</td>
            <td style="padding: 8px;">{{ s.trackingCode }}</td>
            <td style="padding: 8px;">{{ s.carrier }}</td>
            <td style="padding: 8px;">{{ s.status }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else style="color: #999;">No shipments yet.</p>
    </section>

    <section style="margin: 24px 0; padding: 16px; border: 1px solid #ddd; border-radius: 8px;">
      <h2>Report Location</h2>
      <div style="display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 12px; margin-bottom: 12px;">
        <label>Shipment ID <input v-model="locationShipmentId" placeholder="paste shipment id" style="width:100%; padding:6px;" /></label>
        <label>Lat <input v-model.number="lat" type="number" step="0.01" style="width:100%; padding:6px;" /></label>
        <label>Lng <input v-model.number="lng" type="number" step="0.01" style="width:100%; padding:6px;" /></label>
      </div>
      <button @click="reportLocation" style="padding: 8px 20px; background: #34a853; color: white; border: none; border-radius: 4px; cursor: pointer;">
        ReportLocation RPC
      </button>
      <p v-if="locationResult" style="margin-top: 8px; color: green;">{{ locationResult }}</p>
      <p v-if="locationError" style="color: red;">{{ locationError }}</p>
    </section>

    <section style="margin: 24px 0; padding: 16px; border: 1px solid #ddd; border-radius: 8px;">
      <h2>Get Tracking</h2>
      <div style="display: flex; gap: 12px; margin-bottom: 12px;">
        <input v-model="trackingShipmentId" placeholder="shipment id" style="flex:1; padding:6px;" />
        <button @click="getTracking" style="padding: 8px 20px; background: #ea4335; color: white; border: none; border-radius: 4px; cursor: pointer;">
          GetTracking RPC
        </button>
      </div>
      <pre v-if="trackingResult" style="background: #f5f5f5; padding: 12px; border-radius: 4px; font-size: 13px;">{{ trackingResult }}</pre>
      <p v-if="trackingError" style="color: red;">{{ trackingError }}</p>
    </section>

    <footer style="margin-top: 40px; padding-top: 16px; border-top: 1px solid #eee; color: #999; font-size: 13px;">
      gRPC-Web via ConnectRPC transport → Envoy (localhost:8080) → Shipment / Tracking services.
      TypeScript models generated by <code>buf generate</code> with <code>@bufbuild/protobuf</code> + <code>@connectrpc/connect-es</code>.
    </footer>
  </div>
</template>
