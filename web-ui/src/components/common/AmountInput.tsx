export default function AmountInput({
  value,
  symbol,
  disabled,
  onChange
}: {
  value: string
  symbol: string
  disabled: boolean
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
}) {
  return (
    <div className="relative mt-8 rounded-md shadow-sm">
      <input
        type="number"
        name="amount"
        id="amount"
        className="text-gray-900 ring-gray-300 placeholder:text-gray-400 focus:ring-gray-500 block w-full rounded-md border-0 py-1.5 pr-20 ring-1 ring-inset [appearance:textfield] focus:ring-1 focus:ring-inset [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        disabled={disabled}
        placeholder="0"
        value={value}
        onChange={onChange}
      />
      <div className="absolute inset-y-0 right-0 flex items-center">
        <div className="text-gray-500 border-0 px-2">{symbol}</div>
      </div>
    </div>
  )
}
