import { classNames } from 'utils'

export default function SubmitButton({
  disabled,
  onClick,
  caption,
  error
}: {
  disabled: boolean
  onClick: () => void
  caption: () => string
  error: string | null
}) {
  return (
    <>
      <button
        type="button"
        disabled={disabled}
        className={classNames(
          'mt-4 w-full inline-flex justify-center rounded-md border border-transparent px-4 py-2 text-sm font-medium text-white focus:outline-none focus:ring-1 focus:ring-inset',
          disabled
            ? 'bg-gray-300 focus:ring-gray-500'
            : 'bg-green-500 hover:bg-green-600 focus:ring-green-800'
        )}
        onClick={onClick}
      >
        {caption()}
      </button>

      {error && (
        <div className="mt-2 text-center text-sm text-red-500">{error}</div>
      )}
    </>
  )
}
