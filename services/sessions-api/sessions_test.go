package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type stubRepo struct {
	mu          sync.Mutex
	sessions    map[string]*SessionResponse
	idempotency map[string]IdempotentRecord
	outbox      []OutboxFact
	delivered   map[string]bool
}

func newStubRepo() *stubRepo {
	return &stubRepo{
		sessions:    map[string]*SessionResponse{},
		idempotency: map[string]IdempotentRecord{},
		delivered:   map[string]bool{},
	}
}

func (r *stubRepo) GetIdempotency(_ context.Context, key, op, actor string) (*IdempotentRecord, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if rec, ok := r.idempotency[op+":"+actor+":"+key]; ok {
		return &rec, nil
	}
	return nil, nil
}

func (r *stubRepo) CreateSession(_ context.Context, ses *SessionResponse, idemKey, reqHash, actor string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, existing := range r.sessions {
		if existing.ProposalID == ses.ProposalID && (existing.Status == StatusAwaitingAgreement || existing.Status == StatusScheduled || existing.Status == StatusInProgress) {
			return newProblem(http.StatusConflict, "ACTIVE_SESSION_EXISTS", "Sesión activa", "Ya existe una sesión activa.")
		}
	}
	copySes := *ses
	r.sessions[ses.SessionID] = &copySes
	raw, _ := json.Marshal(ses)
	r.idempotency["CREATE_SESSION:"+actor+":"+idemKey] = IdempotentRecord{RequestHash: reqHash, ResponseStatus: 201, ResponseBody: raw}
	return nil
}

func (r *stubRepo) GetSession(_ context.Context, sessionID string) (*SessionResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ses, ok := r.sessions[sessionID]
	if !ok {
		return nil, newProblem(http.StatusNotFound, "SESSION_NOT_FOUND", "No encontrada", "Sesión no existe.")
	}
	copySes := *ses
	copySes.Agreements = append([]ParticipantAgreementState(nil), ses.Agreements...)
	return &copySes, nil
}

func (r *stubRepo) UpdateAgreement(_ context.Context, sessionID, userID, version string, accepted bool, at time.Time, newStatus string) (*SessionResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ses := r.sessions[sessionID]
	for i := range ses.Agreements {
		if ses.Agreements[i].UserID == userID {
			ses.Agreements[i].Accepted = accepted
			ses.Agreements[i].AcceptedAt = &at
			ses.Agreements[i].AgreementVersion = version
		}
	}
	ses.Status = newStatus
	return ses, nil
}

func (r *stubRepo) Reschedule(_ context.Context, sessionID, actor, _, _ string, start, end time.Time, partnerID string) (*SessionResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ses := r.sessions[sessionID]
	ses.ScheduledStartAt = start
	ses.ScheduledEndAt = end
	ses.Status = StatusReschedulePending
	now := time.Now().UTC()
	for i := range ses.Agreements {
		if ses.Agreements[i].UserID == actor {
			ses.Agreements[i].Accepted = true
			if ses.Agreements[i].AcceptedAt == nil {
				ses.Agreements[i].AcceptedAt = &now
			}
		} else if ses.Agreements[i].UserID == partnerID {
			ses.Agreements[i].Accepted = false
			ses.Agreements[i].AcceptedAt = nil
		}
	}
	return ses, nil
}

func (r *stubRepo) CancelAndEnqueueFact(_ context.Context, sessionID, _, _ string, _ bool, fact OutboxFact) (*SessionResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ses := r.sessions[sessionID]
	ses.Status = StatusCancelled
	r.outbox = append(r.outbox, fact)
	return ses, nil
}

func (r *stubRepo) TransitionToInProgressAndLogToken(_ context.Context, sessionID, _ string, _ time.Time) (*SessionResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ses := r.sessions[sessionID]
	if ses.Status == StatusScheduled {
		ses.Status = StatusInProgress
	}
	return ses, nil
}

func (r *stubRepo) CloseSessionAndEnqueueFact(_ context.Context, sessionID, terminalState string, fact OutboxFact) (*SessionResponse, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	ses := r.sessions[sessionID]
	ses.Status = terminalState
	r.outbox = append(r.outbox, fact)
	return ses, nil
}

func (r *stubRepo) ListPendingOutboxFacts(_ context.Context, _ int) ([]OutboxFact, error) { return nil, nil }
func (r *stubRepo) MarkOutboxDelivered(_ context.Context, factReportID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.delivered[factReportID] = true
	return nil
}
func (r *stubRepo) MarkOutboxFailed(_ context.Context, _, _ string) error { return nil }

type stubDomainClient struct {
	valid       bool
	reportedIDs []string
}

func (d *stubDomainClient) ValidateProposal(_ context.Context, proposalID string) (*ProposalValidation, error) {
	var p0, p1 ParticipantValidation
	p0.UserID = "usr_alice"
	p0.Teaches.SkillSlug = "programacion-backend"
	p0.Teaches.TopicCode = "rest-apis"
	p0.Teaches.IsQualified = d.valid
	p1.UserID = "usr_bob"
	p1.Teaches.SkillSlug = "ingles-tecnico"
	p1.Teaches.TopicCode = "technical-interviews"
	p1.Teaches.IsQualified = d.valid
	return &ProposalValidation{
		ProposalID:          proposalID,
		Status:              "ACCEPTED",
		ValidForScheduling:  d.valid,
		Participants:        []ParticipantValidation{p0, p1},
		RecommendedGuideIDs: []string{"50000000-0000-4000-8000-000000000001"},
	}, nil
}

func (d *stubDomainClient) ReportSessionFacts(_ context.Context, fact SessionFactsPayload) error {
	d.reportedIDs = append(d.reportedIDs, fact.FactReportID)
	return nil
}

type stubAuthVerifier struct{}

func (stubAuthVerifier) VerifyUserToken(tok string) (string, error) { return tok, nil }

func TestSessionLifecycleAndRoomTokenSecurity(t *testing.T) {
	now := time.Date(2026, 11, 20, 18, 0, 0, 0, time.UTC)
	repo := newStubRepo()
	dom := &stubDomainClient{valid: true}
	svc := &SessionService{
		Repo: repo, Domain: dom,
		LiveKit:    &LiveKitTokenIssuer{APIKey: "devkey", APISecret: "secret_kanjora_livekit_dev_key_min_32_chars"},
		LiveKitURL: "wss://kanjora.livekit.cloud",
		Clock:      func() time.Time { return now },
	}
	handler := newRouter(svc, stubAuthVerifier{})

	// 1. Rechazar propuesta inválida desde Java (409)
	dom.valid = false
	badReq, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions", bytes.NewBufferString(`{
		"proposalId":"p-1","scheduledStartAt":"2026-11-20T18:30:00Z","scheduledEndAt":"2026-11-20T19:30:00Z",
		"agreementAccepted":true,"agreementVersion":"v1.0-pilot"
	}`))
	badReq.Header.Set("Authorization", "Bearer usr_alice")
	badReq.Header.Set("Idempotency-Key", "idem-invalid-001")
	badResp := httptest.NewRecorder()
	handler.ServeHTTP(badResp, badReq)
	if badResp.Code != http.StatusConflict {
		t.Fatalf("esperaba 409 cuando Java invalida propuesta, obtuve %d", badResp.Code)
	}

	// 2. Crear sesión con idempotencia (201 y replay 200)
	dom.valid = true
	createBody := `{"proposalId":"p-1","scheduledStartAt":"2026-11-20T18:30:00Z","scheduledEndAt":"2026-11-20T19:30:00Z","agreementAccepted":true,"agreementVersion":"v1.0-pilot"}`
	req1, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions", bytes.NewBufferString(createBody))
	req1.Header.Set("Authorization", "Bearer usr_alice")
	req1.Header.Set("Idempotency-Key", "idem-create-001")
	w1 := httptest.NewRecorder()
	handler.ServeHTTP(w1, req1)
	if w1.Code != http.StatusCreated {
		t.Fatalf("esperaba 201 al crear sesión, obtuve %d", w1.Code)
	}
	var created SessionResponse
	_ = json.Unmarshal(w1.Body.Bytes(), &created)

	req2, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions", bytes.NewBufferString(createBody))
	req2.Header.Set("Authorization", "Bearer usr_alice")
	req2.Header.Set("Idempotency-Key", "idem-create-001")
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w2, req2)
	if w2.Code != http.StatusOK || len(repo.sessions) != 1 {
		t.Fatalf("esperaba replay idempotente 200 con 1 sola sesión, code=%d count=%d", w2.Code, len(repo.sessions))
	}

	// 3. No permitir entrar a sala antes de que el segundo participante acepte el acuerdo (409)
	now = time.Date(2026, 11, 20, 18, 25, 0, 0, time.UTC)
	tokBeforeAgree, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions/"+created.SessionID+"/room-token", nil)
	tokBeforeAgree.Header.Set("Authorization", "Bearer usr_alice")
	wBefore := httptest.NewRecorder()
	handler.ServeHTTP(wBefore, tokBeforeAgree)
	if wBefore.Code != http.StatusConflict {
		t.Fatalf("esperaba 409 sin acuerdo bilateral, obtuve %d", wBefore.Code)
	}

	// 4. Bob acepta el acuerdo -> pasa a SCHEDULED
	agreeReq, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions/"+created.SessionID+"/agreement", bytes.NewBufferString(`{"agreementAccepted":true,"agreementVersion":"v1.0-pilot"}`))
	agreeReq.Header.Set("Authorization", "Bearer usr_bob")
	wAgree := httptest.NewRecorder()
	handler.ServeHTTP(wAgree, agreeReq)
	if wAgree.Code != http.StatusOK {
		t.Fatalf("esperaba 200 al aceptar acuerdo, obtuve %d", wAgree.Code)
	}

	// 5. Tercero no autorizado recibe 403 al pedir token de sala
	intruderReq, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions/"+created.SessionID+"/room-token", nil)
	intruderReq.Header.Set("Authorization", "Bearer usr_intruder")
	wIntruder := httptest.NewRecorder()
	handler.ServeHTTP(wIntruder, intruderReq)
	if wIntruder.Code != http.StatusForbidden {
		t.Fatalf("esperaba 403 para tercero en sala, obtuve %d", wIntruder.Code)
	}

	// 6. Participante autorizado recibe token LiveKit firmado de corta duración
	okTokReq, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions/"+created.SessionID+"/room-token", nil)
	okTokReq.Header.Set("Authorization", "Bearer usr_bob")
	wTok := httptest.NewRecorder()
	handler.ServeHTTP(wTok, okTokReq)
	if wTok.Code != http.StatusOK {
		t.Fatalf("esperaba 200 para participante autorizado, obtuve %d", wTok.Code)
	}
	var roomResp RoomTokenResponse
	_ = json.Unmarshal(wTok.Body.Bytes(), &roomResp)
	parsed, err := jwt.Parse(roomResp.AccessToken, func(_ *jwt.Token) (any, error) {
		return []byte("secret_kanjora_livekit_dev_key_min_32_chars"), nil
	}, jwt.WithTimeFunc(func() time.Time { return now }))
	if err != nil || !parsed.Valid {
		t.Fatalf("token LiveKit inválido: %v", err)
	}
	claims := parsed.Claims.(jwt.MapClaims)
	if sub, _ := claims.GetSubject(); sub != "usr_bob" {
		t.Fatalf("esperaba sub=usr_bob, obtuve %q", sub)
	}

	// 7. Cierre de sesión reporta hechos idempotentes a Java (Contrato 4)
	closeReq, _ := http.NewRequest(http.MethodPost, "/api/v1/sessions/"+created.SessionID+"/close", bytes.NewBufferString(`{"terminalSessionState":"COMPLETED_OBSERVED","technicalIncidentObserved":false}`))
	closeReq.Header.Set("Authorization", "Bearer usr_alice")
	wClose := httptest.NewRecorder()
	handler.ServeHTTP(wClose, closeReq)
	if wClose.Code != http.StatusOK || len(dom.reportedIDs) != 1 {
		t.Fatalf("esperaba reporte de hechos a Java, code=%d reported=%d", wClose.Code, len(dom.reportedIDs))
	}
}

