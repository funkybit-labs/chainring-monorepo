import React from 'react'
import { classNames } from 'utils'

type Props = {
  value: string
}

export function ExpandableValue({ value }: Props) {
  return (
    <span
      className={classNames(
        'inline-block overflow-x-clip max-w-[10ch] text-ellipsis hover:max-w-full'
      )}
    >
      {value}
    </span>
  )
}
