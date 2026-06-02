# SchemaForge Java 배포 가이드

## 1. Supabase 설정

1. [supabase.com](https://supabase.com) → New project
2. **SQL Editor** → `schemaforge-react/server/schema.sql` 실행 (동일한 스키마 사용)
3. **Project Settings → API** 에서 복사:
   - `Project URL` → `SUPABASE_URL`
   - `anon/public` key → `SUPABASE_ANON_KEY`
4. **Project Settings → Database → Connection string (URI)** 복사:
   - `jdbc:postgresql://...` 형식으로 변환 → `DB_URL`

## 2. 로컬 개발

```bash
# .env 생성
cp .env.example .env
# 값 채우기...

# 프론트엔드 dev server (포트 3000, Java 백엔드 프록시)
npm run dev

# Java 백엔드 (포트 8080)
./gradlew bootRun
```

## 3. Railway 배포

```bash
npm i -g @railway/cli
railway login
railway init

# 환경변수 설정
railway variables set OPENAI_API_KEY=sk-...
railway variables set SUPABASE_URL=https://xxx.supabase.co
railway variables set SUPABASE_ANON_KEY=eyJ...
railway variables set DB_URL=jdbc:postgresql://db.xxx.supabase.co:5432/postgres
railway variables set DB_USER=postgres
railway variables set DB_PASSWORD=your-password

railway up
```

## 4. 환경 변수 목록

| 변수 | 필수 | 설명 |
|------|------|------|
| `OPENAI_API_KEY` | ✅ | GPT-4o |
| `SUPABASE_URL` | ✅ | Supabase 프로젝트 URL |
| `SUPABASE_ANON_KEY` | ✅ | Supabase anon key (Auth API) |
| `DB_URL` | ✅ | JDBC PostgreSQL URL |
| `DB_USER` | ✅ | postgres |
| `DB_PASSWORD` | ✅ | DB 비밀번호 |
| `PORT` | 자동 | Railway 자동 주입 (기본 8080) |
