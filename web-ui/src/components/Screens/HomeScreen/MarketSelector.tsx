import { Fragment } from 'react'
import { Listbox, Transition } from '@headlessui/react'
import { CheckIcon, ChevronUpDownIcon } from '@heroicons/react/20/solid'
import { Market } from 'ApiClient'
import SymbolIcon from 'components/common/SymbolIcon'

export function MarketSelector({
  markets,
  selected,
  onChange
}: {
  markets: Market[]
  selected: Market
  onChange: (newValue: Market) => void
}) {
  return (
    <div className="w-52">
      <Listbox value={selected} onChange={onChange}>
        <div className="relative mt-1">
          <Listbox.Button className="relative w-full cursor-default rounded-lg border border-black bg-neutralGray py-2 pl-3 pr-10 text-left">
            <MarketTitle market={selected} />
            <span className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-2">
              <ChevronUpDownIcon
                className="size-5 text-black"
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
            <Listbox.Options className="absolute mt-1 max-h-60 w-full overflow-auto rounded-md border border-black bg-neutralGray py-1 text-base shadow-lg ring-1 ring-black/5 focus:outline-none">
              {markets.map((market, idx) => (
                <Listbox.Option
                  key={idx}
                  className={
                    'relative cursor-default select-none py-2 pl-10 pr-4 text-black'
                  }
                  value={market}
                >
                  {({ selected }) => (
                    <>
                      <div
                        className={`block truncate ${
                          selected ? 'font-medium' : 'font-normal'
                        }`}
                      >
                        <MarketTitle market={market} />
                      </div>
                      {selected ? (
                        <span className="absolute inset-y-0 left-0 flex items-center pl-3 text-black">
                          <CheckIcon className="size-5" aria-hidden="true" />
                        </span>
                      ) : null}
                    </>
                  )}
                </Listbox.Option>
              ))}
            </Listbox.Options>
          </Transition>
        </div>
      </Listbox>
    </div>
  )
}

function MarketTitle({ market }: { market: Market }) {
  return (
    <div className="flex place-items-center truncate">
      <SymbolIcon
        symbol={market.baseSymbol}
        className="mr-1 inline-block size-5"
      />
      {market.baseSymbol}
      <span className="mx-1">/</span>
      <SymbolIcon
        symbol={market.quoteSymbol}
        className="mr-1 inline-block size-5"
      />
      {market.quoteSymbol}
    </div>
  )
}
