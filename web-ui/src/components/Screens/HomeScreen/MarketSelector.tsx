import { Fragment } from 'react'
import { Listbox, Transition } from '@headlessui/react'
import { ChevronDownIcon } from '@heroicons/react/20/solid'
import SymbolIcon from 'components/common/SymbolIcon'
import Markets, { Market } from 'markets'

export function MarketSelector({
  markets,
  selected,
  onChange
}: {
  markets: Markets
  selected: Market
  onChange: (newValue: Market) => void
}) {
  return (
    <div className="w-40">
      <Listbox value={selected} onChange={onChange}>
        <div className="relative">
          <Listbox.Button className="relative w-full cursor-default rounded-md bg-darkBluishGray7 py-2 pl-3 pr-10 text-left transition-colors duration-300 ease-in-out hover:bg-darkBluishGray6 hover:text-white">
            <MarketTitle market={selected} />
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
              {markets.map((market) => (
                <Listbox.Option
                  key={market.id}
                  className={
                    'relative cursor-default select-none px-4 py-2 hover:bg-darkBluishGray6 hover:text-white'
                  }
                  value={market}
                >
                  {({ selected }) => (
                    <>
                      <div
                        className={`block truncate ${
                          selected ? 'font-bold text-white' : 'font-normal'
                        }`}
                      >
                        <MarketTitle market={market} />
                      </div>
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
        symbol={market.baseSymbol.name}
        className="inline-block size-4"
      />
      <SymbolIcon
        symbol={market.quoteSymbol.name}
        className="mr-2 inline-block size-4"
      />
      {market.baseSymbol.name}
      <span className="">/</span>
      {market.quoteSymbol.name}
    </div>
  )
}
