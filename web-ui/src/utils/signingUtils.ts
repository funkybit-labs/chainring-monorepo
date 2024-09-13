import { Hex, TypedData, UserRejectedRequestError } from 'viem'
import { signTypedData, SignTypedDataParameters } from '@wagmi/core'
import { wagmiConfig } from 'wagmiConfig'
import SatsConnect, { RpcErrorCode } from 'sats-connect'

export async function bitcoinSignMessage(
  address: string,
  message: string
): Promise<string | null> {
  const result = await SatsConnect.request('signMessage', {
    address,
    message
  })
  if (result.status === 'success') {
    return result.result.signature
  } else if (result.error.code === RpcErrorCode.USER_REJECTION) {
    return null
  } else {
    console.log(result.error)
    throw Error('Failed to sign message with bitcoin wallet')
  }
}

export async function evmSignTypedData<
  const typedData extends TypedData | Record<string, unknown>,
  primaryType extends keyof typedData | 'EIP712Domain'
>(
  parameters: SignTypedDataParameters<typedData, primaryType>
): Promise<Hex | null> {
  try {
    return await signTypedData(wagmiConfig, parameters)
  } catch (e) {
    if (e instanceof UserRejectedRequestError) {
      return null
    } else {
      throw e
    }
  }
}
