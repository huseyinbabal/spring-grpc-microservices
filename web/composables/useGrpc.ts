import { createGrpcWebTransport } from '@connectrpc/connect-web'
import { createPromiseClient } from '@connectrpc/connect'
import { ShipmentService } from '~/proto/cargo/shipment/v1/shipment_connect'
import { TrackingService } from '~/proto/cargo/tracking/v1/tracking_connect'

const transport = createGrpcWebTransport({
  baseUrl: 'http://localhost:8080',
})

export function useGrpc() {
  const shipmentClient = createPromiseClient(ShipmentService, transport)
  const trackingClient = createPromiseClient(TrackingService, transport)

  return { shipmentClient, trackingClient }
}
