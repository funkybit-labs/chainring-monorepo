import mitt, { Emitter } from 'mitt'

type EmitterEvents = {
  authorizedWallet: undefined
}

export const emitter: Emitter<EmitterEvents> = mitt<EmitterEvents>()
