type Props = {
  id: string
  contents: JSX.Element
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  wrapperRef?: any
}

export function Widget({ id, contents, wrapperRef }: Props) {
  return (
    <div id={id} ref={wrapperRef}>
      <div className="rounded-lg bg-darkBluishGray9 p-4 text-white shadow-lg">
        {contents}
      </div>
    </div>
  )
}
