import React, { useState } from 'react'
import { Tooltip } from 'react-tooltip'
import { FaCopy, FaExternalLinkAlt } from 'react-icons/fa'
import { CopyToClipboard } from 'react-copy-to-clipboard'

type Props = {
  txHash: string
  blockExplorerUrl: string | undefined
}

export function TxHashDisplay({ txHash, blockExplorerUrl }: Props) {
  const [copyTooltipText, setCopyTooltipText] = useState('Copy to clipboard')

  const truncateHash = (hash: string, length = 12) => {
    if (hash.length <= length) return hash
    const partLength = Math.floor(length / 2)
    return `${hash.substring(0, partLength + 2)}...${hash.substring(
      hash.length - partLength
    )}`
  }

  const handleCopy = () => {
    setCopyTooltipText('Copied!')
    setTimeout(() => setCopyTooltipText('Copy to clipboard'), 2000)
  }

  return (
    <div className="inline text-center text-xs">
      <div
        className="mr-2 hidden narrow:inline"
        data-tooltip-id="txHashTooltip"
        data-tooltip-content={txHash}
      >
        {truncateHash(txHash)}
      </div>
      <div className="inline items-center justify-center">
        <button
          className="mr-2"
          data-tooltip-id="txCopyTooltip"
          data-tooltip-content={copyTooltipText}
        >
          <CopyToClipboard text={txHash} onCopy={handleCopy}>
            <FaCopy />
          </CopyToClipboard>
        </button>
        {blockExplorerUrl && (
          <button
            className="mr-2"
            data-tooltip-id="txExplorerTooltip"
            data-tooltip-content="Open in block explorer"
            onClick={() =>
              window.open(
                blockExplorerUrl + '/tx/' + txHash,
                '_blank',
                'noopener noreferrer'
              )
            }
          >
            <FaExternalLinkAlt />
          </button>
        )}
      </div>
      <Tooltip id="txHashTooltip" place="top" delayShow={300} />
      <Tooltip id="txCopyTooltip" place="top" delayShow={300} />
      <Tooltip id="txExplorerTooltip" place="top" delayShow={300} />
    </div>
  )
}
