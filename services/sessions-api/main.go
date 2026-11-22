package main

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"slices"
	"strings"
	"syscall"
	"time"

	"github.com/MicahParks/keyfunc/v3"
	"github.com/golang-jwt/jwt/v5"
	_ "github.com/jackc/pgx/v5/stdlib"
)

type TokenVerifier interface {
	VerifyUserToken(tokenString string) (userID string, err error)
}

type OIDCVerifier struct {
	Keyfunc          jwt.Keyfunc
	ExpectedIssuer   string
	RequiredAudience string
}

func (v *OIDCVerifier) VerifyUserToken(tokenString string) (string, error) {
	parsed, err := jwt.Parse(
		tokenString,
		v.Keyfunc,
		jwt.WithIssuer(v.ExpectedIssuer),
		jwt.WithExpirationRequired(),
	)
	if err != nil || !parsed.Valid {
		return "", newProblem(http.StatusUnauthorized, "UNAUTHORIZED", "Token inválido", "El token JWT es inválido o expiró.")
	}
	claims, ok := parsed.Claims.(jwt.MapClaims)
	if !ok {
		return "", newProblem(http.StatusUnauthorized, "UNAUTHORIZED", "Token inválido", "Claims JWT inválidos.")
	}
	audList, err := claims.GetAudience()
	if err != nil || !slices.Contains(audList, v.RequiredAudience) {
		return "", newProblem(http.StatusUnauthorized, "UNAUTHORIZED", "Audiencia inválida", "Falta audiencia kanjora-api en el token.")
	}
	sub, err := claims.GetSubject()
	if err != nil || strings.TrimSpace(sub) == "" {
		return "", newProblem(http.StatusUnauthorized, "UNAUTHORIZED", "Subject requerido", "El token no contiene sub válido.")
	}
	return sub, nil
}

func newRouter(svc *SessionService, verifier TokenVerifier) http.Handler {
	mux := http.NewServeMux()

	withAuth := func(next func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			authHeader := r.Header.Get("Authorization")
			if !strings.HasPrefix(authHeader, "Bearer ") {
				writeProblem(w, r, newProblem(http.StatusUnauthorized, "UNAUTHORIZED", "Autenticación requerida", "Falta header Authorization Bearer."))
				return
			}
			rawToken := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
			userID, err := verifier.VerifyUserToken(rawToken)
			if err != nil {
				handleError(w, r, err)
				return
			}
			next(w, r, userID)
		}
	}

	mux.HandleFunc("POST /api/v1/sessions", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		var req CreateSessionRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeProblem(w, r, newProblem(http.StatusUnprocessableEntity, "INVALID_JSON", "JSON inválido", err.Error()))
			return
		}
		ses, replayed, err := svc.CreateSession(r.Context(), userID, r.Header.Get("Idempotency-Key"), req)
		if err != nil {
			handleError(w, r, err)
			return
		}
		status := http.StatusCreated
		if replayed {
			status = http.StatusOK
		}
		writeJSON(w, status, ses)
	}))

	mux.HandleFunc("GET /api/v1/sessions/{sessionId}", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		ses, err := svc.GetSession(r.Context(), userID, r.PathValue("sessionId"))
		if err != nil {
			handleError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, ses)
	}))

	mux.HandleFunc("POST /api/v1/sessions/{sessionId}/agreement", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		var req AcceptAgreementRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeProblem(w, r, newProblem(http.StatusUnprocessableEntity, "INVALID_JSON", "JSON inválido", err.Error()))
			return
		}
		ses, err := svc.AcceptAgreement(r.Context(), userID, r.PathValue("sessionId"), req)
		if err != nil {
			handleError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, ses)
	}))

	mux.HandleFunc("PATCH /api/v1/sessions/{sessionId}/reschedule", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		var req RescheduleSessionRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeProblem(w, r, newProblem(http.StatusUnprocessableEntity, "INVALID_JSON", "JSON inválido", err.Error()))
			return
		}
		ses, err := svc.RescheduleSession(r.Context(), userID, r.PathValue("sessionId"), r.Header.Get("Idempotency-Key"), req)
		if err != nil {
			handleError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, ses)
	}))

	mux.HandleFunc("POST /api/v1/sessions/{sessionId}/cancel", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		var req CancelSessionRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeProblem(w, r, newProblem(http.StatusUnprocessableEntity, "INVALID_JSON", "JSON inválido", err.Error()))
			return
		}
		ses, err := svc.CancelSession(r.Context(), userID, r.PathValue("sessionId"), req)
		if err != nil {
			handleError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, ses)
	}))

	mux.HandleFunc("POST /api/v1/sessions/{sessionId}/room-token", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		tok, err := svc.IssueRoomToken(r.Context(), userID, r.PathValue("sessionId"))
		if err != nil {
			handleError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, tok)
	}))

	mux.HandleFunc("POST /api/v1/sessions/{sessionId}/close", withAuth(func(w http.ResponseWriter, r *http.Request, userID string) {
		var req CloseSessionRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeProblem(w, r, newProblem(http.StatusUnprocessableEntity, "INVALID_JSON", "JSON inválido", err.Error()))
			return
		}
		ses, err := svc.CloseAndReportFacts(r.Context(), userID, r.PathValue("sessionId"), req)
		if err != nil {
			handleError(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, ses)
	}))

	return mux
}

func handleError(w http.ResponseWriter, r *http.Request, err error) {
	if p, ok := asProblem(err); ok {
		writeProblem(w, r, p)
		return
	}
	writeProblem(w, r, newProblem(http.StatusInternalServerError, "INTERNAL_ERROR", "Error interno", err.Error()))
}

func writeProblem(w http.ResponseWriter, r *http.Request, p *ProblemError) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(p.Status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"type":      "https://kanjora.dev/errors/" + strings.ToLower(p.ErrorCode),
		"title":     p.Title,
		"status":    p.Status,
		"detail":    p.Detail,
		"instance":  r.URL.Path,
		"errorCode": p.ErrorCode,
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func envOr(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

//go:embed migrations/001_init_sessions_schema.sql
var sessionsSchemaSQL string

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dsn := envOr("DATABASE_URL", "postgres://kanjora_sessions_app:kanjora_sessions_dev_pwd@localhost:5432/kanjora?sslmode=disable&search_path=sessions")
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		log.Fatalf("error abriendo postgresql: %v", err)
	}
	defer db.Close()

	if _, err := db.ExecContext(ctx, sessionsSchemaSQL); err != nil {
		log.Fatalf("error ejecutando migración en esquema sessions: %v", err)
	}

	jwksURL := envOr("OIDC_JWK_SET_URI", "http://localhost:8180/realms/kanjora/protocol/openid-connect/certs")
	k, err := keyfunc.NewDefaultCtx(ctx, []string{jwksURL})
	if err != nil {
		log.Fatalf("error iniciando JWKS: %v", err)
	}

	svc := &SessionService{
		Repo: &PostgresSessionRepo{DB: db},
		Domain: &HTTPDomainClient{
			BaseURL:      envOr("DOMAIN_API_URL", "http://localhost:8080"),
			ServiceToken: envOr("DOMAIN_SERVICE_TOKEN", ""),
			TokenURL:     envOr("OIDC_TOKEN_URI", "http://localhost:8180/realms/kanjora/protocol/openid-connect/token"),
			ClientID:     envOr("OIDC_CLIENT_ID", "kanjora-sessions-service"),
			ClientSecret: envOr("OIDC_CLIENT_SECRET", "kanjora-sessions-dev-secret"),
			HTTP:         &http.Client{Timeout: 5 * time.Second},
		},
		LiveKit: &LiveKitTokenIssuer{
			APIKey:    envOr("LIVEKIT_API_KEY", "devkey"),
			APISecret: envOr("LIVEKIT_API_SECRET", "secret_kanjora_livekit_dev_key_min_32_chars"),
		},
		LiveKitURL: envOr("LIVEKIT_URL", "ws://localhost:7880"),
		Clock:      time.Now,
	}

	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				svc.FlushOutbox(ctx)
			}
		}
	}()

	addr := ":" + envOr("PORT", "8081")
	srv := &http.Server{Addr: addr, Handler: newRouter(svc, &OIDCVerifier{
		Keyfunc:          k.Keyfunc,
		ExpectedIssuer:   envOr("OIDC_ISSUER_URI", "http://localhost:8180/realms/kanjora"),
		RequiredAudience: envOr("OIDC_REQUIRED_AUDIENCE", "kanjora-api"),
	})}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	log.Printf("Kanjora Sessions API escuchando en %s", addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("error en servidor HTTP: %v", err)
	}
}
