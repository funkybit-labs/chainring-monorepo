type Props = {
  title: string
  contents: JSX.Element
}

export function Widget({ title, contents }: Props) {
  return (
    <div>
      <div className="mb-2 text-center text-xl text-black">{title}</div>
      <div className="min-h-40 w-full min-w-48 rounded-lg bg-black p-4 text-white shadow-lg">
        {contents}
      </div>
    </div>
  )
}
