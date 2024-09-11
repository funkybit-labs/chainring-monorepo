import { useEffect, createContext, useRef, useContext } from 'react'
import { ExponentialBackoff, Websocket, WebsocketBuilder } from 'websocket-ts'
import { apiBaseUrl } from 'apiClient'
import { loadAuthToken } from 'auth'
import {
  IncomingWSMessage,
  Publishable,
  PublishableSchema,
  SubscriptionTopic
} from 'websocketMessages'
import { Wallet } from 'contexts/walletProvider'

const connectionUrl =
  apiBaseUrl.replace('http:', 'ws:').replace('https:', 'wss:') + '/connect'

const CloseEventCodeUnauthorized = 3000

type SubscriptionEventHandler = (data: Publishable) => void
type UnsubscriptionHandler = () => void

export const WebsocketContext = createContext<{
  subscribe: (
    topic: SubscriptionTopic,
    handler: SubscriptionEventHandler
  ) => void
  unsubscribe: (
    topic: SubscriptionTopic,
    handler: SubscriptionEventHandler
  ) => void
} | null>(null)

export function WebsocketProvider({
  wallet,
  children
}: {
  wallet: Wallet
  children: React.ReactNode
}) {
  const ws = useRef<Websocket | null>(null)
  const subscriptions = useRef<Map<string, SubscriptionEventHandler[]>>(
    new Map()
  )

  function subscribe(
    topic: SubscriptionTopic,
    handler: SubscriptionEventHandler
  ) {
    const stringifiedTopic = JSON.stringify(topic)
    const topicSubscribers = subscriptions.current.get(stringifiedTopic) || []
    if (!topicSubscribers.includes(handler)) {
      topicSubscribers.push(handler)
      sendSubscribeMessage(topic)
    }
    subscriptions.current.set(stringifiedTopic, topicSubscribers)
  }

  function unsubscribe(
    topic: SubscriptionTopic,
    handler: SubscriptionEventHandler
  ) {
    const stringifiedTopic = JSON.stringify(topic)
    const topicSubscribers = subscriptions.current.get(stringifiedTopic)
    if (topicSubscribers) {
      sendUnsubscribeMessage(topic)
      const idx = topicSubscribers.indexOf(handler)
      if (idx != -1) {
        topicSubscribers.splice(idx, 1)
        if (topicSubscribers.length == 0) {
          subscriptions.current.delete(stringifiedTopic)
        }
      }
    }
  }

  function sendSubscribeMessage(topic: SubscriptionTopic) {
    ws.current?.send(
      JSON.stringify({
        type: 'Subscribe',
        topic: topic
      })
    )
  }

  function sendUnsubscribeMessage(topic: SubscriptionTopic) {
    ws.current?.send(
      JSON.stringify({
        type: 'Unsubscribe',
        topic: topic
      })
    )
  }

  function handleMessage(ws: Websocket, event: MessageEvent) {
    const message = JSON.parse(event.data) as IncomingWSMessage
    if (
      'type' in message &&
      message.type === 'Publish' &&
      'topic' in message &&
      'data' in message
    ) {
      const stringifiedTopic = JSON.stringify(message.topic)
      const handlers = subscriptions.current.get(stringifiedTopic)
      if (handlers) {
        const parseResult = PublishableSchema.safeParse(message.data)
        if (parseResult.success) {
          handlers.forEach((handler) => {
            handler(parseResult.data)
          })
        }
      }
    }
  }

  useEffect(() => {
    let connecting = false

    function restoreSubscriptions() {
      for (const stringifiedTopic of subscriptions.current.keys()) {
        sendSubscribeMessage(JSON.parse(stringifiedTopic))
      }
    }

    async function connect(refreshAuth: boolean = false) {
      // don't do anything until we know for sure that wallet is connected
      if (
        wallet.primaryCategory === 'evm' &&
        ['connecting', 'reconnecting'].includes(
          wallet.evmAccount?.status?.toString() || ''
        )
      )
        return

      if (connecting) return
      connecting = true

      const authQuery = wallet.primaryAddress
        ? `?auth=${await loadAuthToken({ forceRefresh: refreshAuth })}`
        : ''

      ws.current?.close()
      ws.current = new WebsocketBuilder(connectionUrl + authQuery)
        .withBackoff(new ExponentialBackoff(1000, 4))
        .onMessage(handleMessage)
        .onOpen(() => {
          restoreSubscriptions()
        })
        .onReconnect(() => {
          restoreSubscriptions()
        })
        .onClose((ws, event) => {
          if (event.code === CloseEventCodeUnauthorized) {
            connect(true)
          }
        })
        .build()

      connecting = false
    }

    connect()

    return () => {
      ws.current?.close()
    }
  }, [wallet.primaryCategory, wallet.primaryAddress, wallet.evmAccount])

  return (
    <WebsocketContext.Provider value={{ subscribe, unsubscribe }}>
      {children}
    </WebsocketContext.Provider>
  )
}

export function useWebsocketSubscription({
  topics,
  handler,
  onUnsubscribe
}: {
  topics: SubscriptionTopic[]
  handler: SubscriptionEventHandler
  onUnsubscribe?: UnsubscriptionHandler
}) {
  const context = useContext(WebsocketContext)
  if (!context) {
    throw Error(
      'No websocket context found, please make sure the component using this hook is wrapped into WebsocketProvider'
    )
  }
  const { subscribe, unsubscribe } = context

  useEffect(() => {
    topics.forEach((t) => subscribe(t, handler))

    return () => {
      topics.forEach((t) => unsubscribe(t, handler))
      if (onUnsubscribe) onUnsubscribe()
    }
  }, [topics, subscribe, unsubscribe, handler, onUnsubscribe])
}
