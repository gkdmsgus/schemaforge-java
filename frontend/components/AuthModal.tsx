import { useState, useEffect, useRef } from 'react'
import { login, register, saveAuth, type AuthUser } from '../api'

interface AuthModalProps {
  open: boolean
  onClose: () => void
  onSuccess: (user: AuthUser, token: string) => void
}

type Mode = 'login' | 'register'

export default function AuthModal({ open, onClose, onSuccess }: AuthModalProps) {
  const [mode, setMode]       = useState<Mode>('login')
  const [email, setEmail]     = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError]     = useState('')
  const [loading, setLoading] = useState(false)
  const [success, setSuccess] = useState(false)
  const emailRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (open) {
      setError(''); setSuccess(false)
      setTimeout(() => emailRef.current?.focus(), 80)
    }
  }, [open, mode])

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose])

  if (!open) return null

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    if (mode === 'register' && password !== confirm) {
      setError('비밀번호가 일치하지 않습니다.')
      return
    }
    setLoading(true)
    try {
      const fn = mode === 'login' ? login : register
      const { user, token } = await fn(email, password)
      saveAuth(user, token)
      setSuccess(true)
      setTimeout(() => { onSuccess(user, token); onClose() }, 700)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  function switchMode(next: Mode) {
    setMode(next); setError(''); setConfirm('')
  }

  const isLogin = mode === 'login'

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 200,
        background: 'rgba(26,22,17,0.55)',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        padding: 20, animation: 'sfFadeIn 0.15s ease',
      }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          width: '100%', maxWidth: 420,
          background: 'var(--sf-bg-2)',
          border: '1px solid var(--sf-line-strong)',
          borderRadius: 20,
          boxShadow: '0 24px 64px rgba(74,52,18,0.22), 0 4px 16px rgba(74,52,18,0.12)',
          overflow: 'hidden',
          animation: 'sfSlideUp 0.2s cubic-bezier(0.34,1.56,0.64,1)',
        }}
      >
        {/* Top accent bar */}
        <div style={{ height: 4, background: 'linear-gradient(90deg, var(--sf-amber) 0%, var(--sf-amber-deep) 100%)' }} />

        <div style={{ padding: '32px 32px 28px' }}>

          {/* Header */}
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 28 }}>
            <div>
              {/* Mode tabs */}
              <div style={{ display: 'flex', gap: 4, marginBottom: 16, padding: 4, background: 'var(--sf-bg-3)', borderRadius: 10, width: 'fit-content' }}>
                {(['login', 'register'] as Mode[]).map(m => (
                  <button
                    key={m}
                    onClick={() => switchMode(m)}
                    style={{
                      padding: '6px 18px', borderRadius: 7, border: 'none', cursor: 'pointer',
                      fontFamily: 'var(--sf-font-sans)', fontSize: 13, fontWeight: 600,
                      transition: 'all 0.15s',
                      background: mode === m ? 'var(--sf-bg-2)' : 'transparent',
                      color: mode === m ? 'var(--sf-fg)' : 'var(--sf-fg-dim)',
                      boxShadow: mode === m ? '0 1px 4px rgba(74,52,18,0.12)' : 'none',
                    }}
                  >
                    {m === 'login' ? '로그인' : '회원가입'}
                  </button>
                ))}
              </div>

              <h2 style={{ margin: 0, fontFamily: 'var(--sf-font-sans)', fontSize: 24, fontWeight: 700, color: 'var(--sf-fg)', lineHeight: 1.2 }}>
                {isLogin ? '다시 만나요 👋' : 'SchemaForge 시작하기'}
              </h2>
              <p style={{ margin: '6px 0 0', fontSize: 13.5, color: 'var(--sf-fg-dim)', lineHeight: 1.5 }}>
                {isLogin
                  ? '세션과 즐겨찾기가 기다리고 있어요.'
                  : '회로 설계를 AI와 함께 시작하세요.'}
              </p>
            </div>

            <button
              onClick={onClose}
              style={{
                width: 32, height: 32, borderRadius: '50%',
                background: 'var(--sf-bg-3)', border: '1px solid var(--sf-line)',
                color: 'var(--sf-fg-dim)', cursor: 'pointer',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 16, lineHeight: 1, flexShrink: 0,
                transition: 'all 0.15s',
              }}
              onMouseEnter={e => { e.currentTarget.style.background = 'var(--sf-bg-4)'; e.currentTarget.style.color = 'var(--sf-fg)' }}
              onMouseLeave={e => { e.currentTarget.style.background = 'var(--sf-bg-3)'; e.currentTarget.style.color = 'var(--sf-fg-dim)' }}
            >×</button>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Field label="이메일">
              <input
                ref={emailRef}
                type="email"
                placeholder="name@example.com"
                value={email}
                onChange={e => setEmail(e.target.value)}
                required
                style={inputStyle}
                onFocus={e => e.currentTarget.style.borderColor = 'var(--sf-amber)'}
                onBlur={e => e.currentTarget.style.borderColor = 'var(--sf-line-strong)'}
              />
            </Field>

            <Field label="비밀번호">
              <input
                type="password"
                placeholder={isLogin ? '비밀번호' : '6자 이상'}
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
                minLength={6}
                style={inputStyle}
                onFocus={e => e.currentTarget.style.borderColor = 'var(--sf-amber)'}
                onBlur={e => e.currentTarget.style.borderColor = 'var(--sf-line-strong)'}
              />
            </Field>

            {!isLogin && (
              <Field label="비밀번호 확인">
                <input
                  type="password"
                  placeholder="비밀번호 재입력"
                  value={confirm}
                  onChange={e => setConfirm(e.target.value)}
                  required
                  style={{
                    ...inputStyle,
                    borderColor: confirm && confirm !== password ? '#c0392b' : 'var(--sf-line-strong)',
                  }}
                  onFocus={e => e.currentTarget.style.borderColor = confirm !== password ? '#c0392b' : 'var(--sf-amber)'}
                  onBlur={e => e.currentTarget.style.borderColor = confirm && confirm !== password ? '#c0392b' : 'var(--sf-line-strong)'}
                />
              </Field>
            )}

            {/* Error */}
            {error && (
              <div style={{
                display: 'flex', alignItems: 'center', gap: 8,
                padding: '10px 14px', borderRadius: 10,
                background: 'rgba(176,72,38,0.08)',
                border: '1px solid rgba(176,72,38,0.2)',
                fontSize: 13, color: 'var(--sf-danger)',
              }}>
                <span style={{ fontSize: 16 }}>⚠</span>
                {error}
              </div>
            )}

            {/* Submit */}
            <button
              type="submit"
              disabled={loading || success}
              style={{
                marginTop: 4,
                padding: '13px 24px',
                background: success
                  ? 'var(--sf-cyan)'
                  : 'var(--sf-amber)',
                border: 'none', borderRadius: 12,
                color: '#fff', fontFamily: 'var(--sf-font-sans)',
                fontSize: 15, fontWeight: 700,
                cursor: loading || success ? 'default' : 'pointer',
                transition: 'all 0.2s',
                opacity: loading ? 0.8 : 1,
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
              }}
              onMouseEnter={e => { if (!loading && !success) e.currentTarget.style.filter = 'brightness(1.08)' }}
              onMouseLeave={e => { e.currentTarget.style.filter = 'none' }}
            >
              {success ? (
                <><span>✓</span> 완료!</>
              ) : loading ? (
                <><Spinner /> {isLogin ? '로그인 중...' : '계정 만드는 중...'}</>
              ) : (
                isLogin ? '로그인' : '계정 만들기 →'
              )}
            </button>
          </form>

          {/* Benefits (register only) */}
          {!isLogin && (
            <div style={{ marginTop: 20, display: 'flex', flexDirection: 'column', gap: 6 }}>
              {[
                ['💾', '회로 세션 자동 저장'],
                ['⭐', '즐겨찾기로 빠른 재접근'],
                ['💬', 'AI 채팅 히스토리 보관'],
              ].map(([icon, text]) => (
                <div key={text} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, color: 'var(--sf-fg-dim)' }}>
                  <span style={{ fontSize: 14 }}>{icon}</span> {text}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Bottom switch */}
        <div style={{
          padding: '14px 32px',
          borderTop: '1px solid var(--sf-line)',
          background: 'var(--sf-bg-3)',
          textAlign: 'center',
          fontSize: 13, color: 'var(--sf-fg-dim)',
        }}>
          {isLogin ? '계정이 없으신가요? ' : '이미 계정이 있으신가요? '}
          <button
            onClick={() => switchMode(isLogin ? 'register' : 'login')}
            style={{
              background: 'none', border: 'none',
              color: 'var(--sf-amber)', cursor: 'pointer',
              fontWeight: 700, fontSize: 13, padding: 0,
              fontFamily: 'var(--sf-font-sans)',
            }}
          >
            {isLogin ? '회원가입' : '로그인'}
          </button>
        </div>
      </div>

      <style>{`
        @keyframes sfFadeIn  { from { opacity: 0 } to { opacity: 1 } }
        @keyframes sfSlideUp { from { opacity: 0; transform: translateY(16px) scale(0.97) } to { opacity: 1; transform: none } }
        @keyframes sfSpin    { to { transform: rotate(360deg) } }
      `}</style>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--sf-fg-muted)', fontFamily: 'var(--sf-font-mono)', letterSpacing: '0.05em' }}>
        {label}
      </label>
      {children}
    </div>
  )
}

function Spinner() {
  return (
    <span style={{
      width: 14, height: 14, borderRadius: '50%',
      border: '2px solid rgba(255,255,255,0.3)',
      borderTopColor: '#fff',
      display: 'inline-block',
      animation: 'sfSpin 0.7s linear infinite',
    }} />
  )
}

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '11px 14px',
  background: 'var(--sf-bg)',
  border: '1.5px solid var(--sf-line-strong)',
  borderRadius: 10,
  color: 'var(--sf-fg)',
  fontFamily: 'var(--sf-font-sans)', fontSize: 14,
  boxSizing: 'border-box', outline: 'none',
  transition: 'border-color 0.15s',
}
