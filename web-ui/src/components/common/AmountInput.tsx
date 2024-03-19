export default function AmountInput({
  value,
  symbol,
  onChange
}: {
  value: string
  symbol: string
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
}) {
  return (
    <div className="relative mt-8 rounded-md shadow-sm">
      <input
        type="number"
        name="amount"
        id="amount"
        className="block w-full rounded-md border-0 py-1.5 pr-20 text-gray-900 ring-1 ring-inset ring-gray-300 [appearance:textfield] placeholder:text-gray-400 focus:ring-1 focus:ring-inset focus:ring-gray-500 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        placeholder="0"
        value={value}
        onChange={onChange}
      />
      <div className="absolute inset-y-0 right-0 flex items-center">
        <div className="border-0 px-2 text-gray-500">{symbol}</div>
      </div>
    </div>
  )
}
