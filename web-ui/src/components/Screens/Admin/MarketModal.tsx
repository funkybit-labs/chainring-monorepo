import { useMemo, useState } from 'react'
import { Modal } from 'components/common/Modal'
import { AdminMarket, apiClient } from 'apiClient'
import { useMutation, useQuery } from '@tanstack/react-query'
import Input from 'components/Screens/Admin/Input'
import SubmitButton from 'components/common/SubmitButton'
import Decimal from 'decimal.js'

export default function MarketModal({
  market: initialMarket,
  isOpen,
  close,
  onClosed
}: {
  market?: AdminMarket
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const adding = initialMarket === undefined
  const [market, setMarket] = useState<AdminMarket>(
    initialMarket ?? {
      id: '',
      tickSize: new Decimal(0),
      lastPrice: new Decimal(0),
      minFee: 0n
    }
  )

  const addMarket = useMutation({
    mutationFn: async () => {
      await apiClient.addMarket(market)
    },
    onError: () => {
      setTimeout(addMarket.reset, 3000)
    },
    onSuccess: () => {
      close()
      onClosed()
    }
  })

  const patchMarket = useMutation({
    mutationFn: async () => {
      await apiClient.patchMarket(market, { params: { base, quote } })
    },
    onError: () => {
      setTimeout(patchMarket.reset, 3000)
    },
    onSuccess: () => {
      close()
      onClosed()
    }
  })

  const base = useMemo(() => {
    return market.id.replace(new RegExp('/.*$', ''), '')
  }, [market.id])

  const quote = useMemo(() => {
    return market.id.replace(new RegExp('^.*/', ''), '')
  }, [market.id])

  const symbolsQuery = useQuery({
    queryKey: ['symbols'],
    queryFn: apiClient.listSymbols
  })

  const symbols = useMemo(() => {
    return symbolsQuery.data ?? []
  }, [symbolsQuery.data])

  const canSubmit = useMemo(() => {
    return market.id != '' && market.lastPrice.comparedTo(new Decimal(0)) != 0
  }, [market.id, market.lastPrice])

  const [tickSizeValue, setTickSizeValue] = useState(
    initialMarket?.tickSize?.toString() ?? ''
  )
  const [lastPriceValue, setLastPriceValue] = useState(
    initialMarket?.lastPrice?.toString() ?? ''
  )
  const [minFeeValue, setMinFeeValue] = useState(
    initialMarket?.minFee?.toString() ?? ''
  )

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={adding ? `Add Market` : `Edit ${market.id}`}
    >
      <div className="overflow-y-auto">
        <div className="flex flex-col rounded-lg bg-darkBluishGray8 p-4 text-white">
          <div className="font-bold underline">Base</div>
          <div>
            <select
              disabled={!adding}
              value={base}
              className="bg-darkBluishGray9"
              onChange={(e) => {
                setMarket({ ...market, id: `${e.target.value}/${quote}` })
              }}
            >
              {symbols
                .filter((s) => s.name !== quote)
                .toSorted((a, b) => a.name.localeCompare(b.name))
                .map((symbol) => {
                  return (
                    <option key={symbol.name} value={symbol.name}>
                      {symbol.name}
                    </option>
                  )
                })}
            </select>
          </div>
          <div className="mt-2 font-bold underline">Quote</div>
          <div>
            <select
              disabled={!adding}
              value={quote}
              className="bg-darkBluishGray9"
              onChange={(e) => {
                setMarket({ ...market, id: `${base}/${e.target.value}` })
              }}
            >
              {symbols
                .filter((s) => s.name !== base)
                .toSorted((a, b) => a.name.localeCompare(b.name))
                .map((symbol) => {
                  return (
                    <option key={symbol.name} value={symbol.name}>
                      {symbol.name}
                    </option>
                  )
                })}
            </select>
          </div>
          <div className="mt-2 font-bold underline">Tick Size</div>
          <div>
            <Input
              placeholder={'Tick Size'}
              disabled={!adding}
              value={tickSizeValue}
              onChange={(tickSize) => {
                setTickSizeValue(tickSize)
                setMarket({ ...market, tickSize: new Decimal(tickSize) })
              }}
            />
          </div>
          {adding && (
            <>
              <div className="mt-2 font-bold underline">Initial Price</div>
              <div>
                <Input
                  placeholder={'Initial Price'}
                  disabled={!adding}
                  value={lastPriceValue}
                  onChange={(lastPrice) => {
                    setLastPriceValue(lastPrice)
                    setMarket({ ...market, lastPrice: new Decimal(lastPrice) })
                  }}
                />
              </div>
            </>
          )}
          <div className="mt-2 font-bold underline">Minimum Fee</div>
          <div>
            <Input
              placeholder={'Minimum Fee (in quote units)'}
              disabled={false}
              value={minFeeValue}
              onChange={(minFee) => {
                setMinFeeValue(minFee)
                setMarket({ ...market, minFee: BigInt(minFee) })
              }}
            />
          </div>
          <SubmitButton
            disabled={!canSubmit}
            onClick={() => {
              if (adding) {
                addMarket.mutate()
              } else {
                patchMarket.mutate()
              }
            }}
            caption={() => (adding ? 'Add' : 'Edit')}
            error={
              adding ? addMarket.error?.message : patchMarket.error?.message
            }
            status={adding ? addMarket.status : patchMarket.status}
          />
        </div>
      </div>
    </Modal>
  )
}
