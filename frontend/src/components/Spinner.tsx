interface SpinnerProps {
  size?: 'sm' | 'md';
  /** Accessible label; when omitted the spinner is decorative. */
  label?: string;
}

export function Spinner({ size = 'md', label }: SpinnerProps) {
  return label ? (
    <span className={`spinner spinner--${size}`} role="status" aria-label={label} />
  ) : (
    <span className={`spinner spinner--${size}`} aria-hidden="true" />
  );
}
