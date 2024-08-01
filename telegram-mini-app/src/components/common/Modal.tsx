import React, { Fragment } from 'react'
import { Dialog, Transition } from '@headlessui/react'
import XSvg from 'assets/X.svg'

export function Modal({
  isOpen,
  close,
  onClosed,
  children
}: {
  isOpen: boolean
  close: () => void
  onClosed: () => void
  children: JSX.Element | JSX.Element[]
}) {
  return (
    <>
      <div className="fixed left-0 top-0 z-20 h-screen w-screen opacity-95 backdrop-blur"></div>
      <Transition appear show={isOpen} as={Fragment}>
        <Dialog as="div" className="relative z-30" onClose={close}>
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
            afterLeave={onClosed}
          >
            <div className="fixed inset-0 bg-black/25" />
          </Transition.Child>

          <div className="fixed inset-0 overflow-y-auto">
            <div className="mx-6 flex min-h-full items-center justify-center text-center">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 scale-95"
                enterTo="opacity-100 scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 scale-100"
                leaveTo="opacity-0 scale-95"
              >
                <Dialog.Panel className="mx-4 w-full overflow-hidden rounded-2xl bg-modalBlue text-left align-middle transition-all">
                  <div className="flex flex-col">
                    <span
                      className="mx-2 my-1 cursor-pointer self-end text-white"
                      onClick={() => close()}
                    >
                      <img
                        className="relative -left-2 top-2 size-3"
                        src={XSvg}
                        alt="close"
                      />
                    </span>
                    <div className="p-5 pt-0">{children}</div>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition>
    </>
  )
}
