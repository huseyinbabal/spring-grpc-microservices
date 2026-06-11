import { createGrpcWebTransport } from '@connectrpc/connect-web'
import { createPromiseClient, type Interceptor } from '@connectrpc/connect'
import type Keycloak from 'keycloak-js'
import { ShipmentService } from '~/proto/cargo/shipment/v1/shipment_connect'
import { TrackingService } from '~/proto/cargo/tracking/v1/tracking_connect'

// The gRPC services require a Keycloak-issued JWT on every call. The
// user logs in via Authorization Code + PKCE (see
// plugins/keycloak.client.ts); this interceptor refreshes the access
// token when it has <30s left and attaches it to every gRPC-Web call.
export function useGrpc() {
  const keycloak = useNuxtApp().$keycloak as Keycloak | undefined

  const authInterceptor: Interceptor = (next) => async (req) => {
    if (keycloak) {
      try {
        await keycloak.updateToken(30)
      } catch {
        await keycloak.login()
      }
      req.header.set('Authorization', `Bearer ${keycloak.token}`)
    }
    return next(req)
  }

  const transport = createGrpcWebTransport({
    baseUrl: 'http://localhost:8080',
    interceptors: [authInterceptor],
  })

  const shipmentClient = createPromiseClient(ShipmentService, transport)
  const trackingClient = createPromiseClient(TrackingService, transport)

  return { shipmentClient, trackingClient }
}
