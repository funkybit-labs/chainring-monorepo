import { apiClient } from 'apiClient'
import { Address } from 'viem'
import { BaseError as WagmiError } from 'wagmi'
import { Fragment, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Modal } from 'components/common/Modal'
import SubmitButton from 'components/common/SubmitButton'
import { isErrorFromAlias } from '@zodios/core'
import TradingSymbol from 'tradingSymbol'
import { Listbox, Transition } from '@headlessui/react'
import { ChevronDownIcon } from '@heroicons/react/20/solid'
import SymbolIcon from 'components/common/SymbolIcon'
import { classNames } from 'utils'

export function FaucetModal({
  walletAddress,
  symbols,
  isOpen,
  close
}: {
  walletAddress: Address
  symbols: TradingSymbol[]
  isOpen: boolean
  close: () => void
}) {
  const [selectedSymbol, setSelectedSymbol] = useState<TradingSymbol | null>(
    symbols.length > 0 ? symbols[0] : null
  )

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        return await apiClient.faucet({
          chainId: selectedSymbol!.chainId,
          address: walletAddress
        })
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'faucet', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },

    onSuccess: () => {
      setSubmitPhase('sent')
    }
  })

  const [phase, setSubmitPhase] = useState<'sending' | 'sent' | null>(null)

  async function onSubmit() {
    switch (phase) {
      case 'sending':
        break
      case 'sent':
        close()
        break
      case null:
        mutation.mutate()
    }
  }

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={() => {}}
      title={"Chainring's Testnet Faucet"}
    >
      <div className="w-full overflow-y-auto text-sm text-white ">
        <div className="mb-4 flex flex-col gap-2">
          <div>
            Obtain Testnet Tokens and try our product. We will send you 0.1 of
            the selected token to you connected wallet&apos;s address. Testnet
            tokens have no financial value and cannot be traded at a real price.
          </div>

          <Listbox value={selectedSymbol} onChange={setSelectedSymbol}>
            <div className="relative">
              <Listbox.Button className="relative w-full cursor-default rounded-md bg-darkBluishGray7 py-2 pl-3 pr-10 text-left transition-colors duration-300 ease-in-out hover:bg-darkBluishGray6 hover:text-white">
                <FaucetChain symbol={selectedSymbol!} selected={false} />
                <span className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-2">
                  <ChevronDownIcon
                    className="size-5 text-darkBluishGray1"
                    aria-hidden="true"
                  />
                </span>
              </Listbox.Button>
              <Transition
                as={Fragment}
                leave="transition ease-in duration-100"
                leaveFrom="opacity-100"
                leaveTo="opacity-0"
              >
                <Listbox.Options className="absolute mt-1 max-h-60 w-full overflow-auto rounded-md bg-darkBluishGray7 py-1 text-sm shadow-lg ring-1 ring-black/5 focus:outline-none">
                  {symbols.map((symbol) => (
                    <Listbox.Option
                      key={symbol.name}
                      className={
                        'relative cursor-default select-none px-4 py-2 hover:bg-darkBluishGray6 hover:text-white'
                      }
                      value={symbol}
                    >
                      {({ selected }) => (
                        <FaucetChain symbol={symbol} selected={selected} />
                      )}
                    </Listbox.Option>
                  ))}
                </Listbox.Options>
              </Transition>
            </div>
          </Listbox>

          <div className="mt-4">
            <div className="mr-2 text-darkBluishGray1">Address:</div>
            <div>{walletAddress}</div>
          </div>
          <div className="mt-2">
            <div className="mr-2 text-darkBluishGray1">Amount:</div>
            <div>0.1 {selectedSymbol?.name}</div>
          </div>

          {phase == 'sent' ? (
            <div className="mt-2 flex text-center text-statusGreen">
              Success. Your wallet balance will be updated shortly.
            </div>
          ) : (
            <></>
          )}
        </div>
        <SubmitButton
          disabled={!selectedSymbol || mutation.isPending}
          onClick={onSubmit}
          error={mutation.error?.message}
          caption={() => {
            switch (phase) {
              case 'sending':
                return 'Sending'
              case 'sent':
                return 'Close'
              case null:
                return 'Send'
            }
          }}
          status={mutation.status}
        />
      </div>
    </Modal>
  )
}

function FaucetChain({
  symbol,
  selected
}: {
  symbol: TradingSymbol
  selected: boolean
}) {
  return (
    <div className={classNames('flex place-items-center truncate')}>
      <SymbolIcon
        symbol={symbol.name}
        className="relative mr-2 inline-block size-4"
      />
      <div
        className={`block truncate ${
          selected ? 'font-bold text-white' : 'font-normal'
        }`}
      >
        {symbol.name}
      </div>
    </div>
  )
}
