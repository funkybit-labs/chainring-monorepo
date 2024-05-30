import { classNames } from 'utils'

export default function AmountInput({
  value,
  disabled,
  onChange,
  className
}: {
  value: string
  disabled: boolean
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
  className?: string
}) {
  return (
    <div className="relative grow rounded-xl">
      <input
        className={classNames(
          'block w-full rounded-xl border-0',
          'bg-darkBluishGray9 py-3 text-white',
          'ring-1 ring-inset ring-darkBluishGray6 focus:ring-1 focus:ring-inset focus:ring-mutedGray',
          '[appearance:textfield] placeholder:text-darkBluishGray2',
          '[&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none',
          className
        )}
        disabled={disabled}
        placeholder="0"
        value={value}
        onChange={onChange}
      />
    </div>
  )
}
