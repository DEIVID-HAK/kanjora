package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// Estados de la máquina de sesiones de Kanjora (alineados con sessions-api-v1.yaml)
const (
	StatusAwaitingAgreement       = "AWAITING_PARTNER_AGREEMENT"
	StatusScheduled               = "SCHEDULED"
	StatusReschedulePending       = "RESCHEDULE_PENDING_ACCEPTANCE"
	StatusInProgress              = "IN_PROGRESS"
	StatusCompletedObserved       = "COMPLETED_OBSERVED"
	StatusCancelled               = "CANCELLED"
	StatusNoShowObserved          = "NO_SHOW_OBSERVED"
	StatusTechnicalFailure        = "TECHNICAL_FAILURE_OBSERVED"
	StatusAbandonedMidCall        = "ABANDONED_MID_CALL"
	maxLiveKitTTL                 = 20 * time.Minute
	earlyJoinWindow               = 10 * time.Minute
)

// ProblemError representa errores de dominio en formato RFC 9457.
type ProblemError struct {
	Status    int
	ErrorCode string
	Title     string
	Detail    string
}

func (e *ProblemError) Error() string { return e.Detail }

func newProblem(status int, code, title, detail string) *ProblemError {
	return &ProblemError{Status: status, ErrorCode: code, Title: title, Detail: detail}
}

type SessionBlock struct {
	BlockOrder      int    `json:"blockOrder"`
	DurationMinutes int    `json:"durationMinutes"`
	TeacherUserID   string `json:"teacherUserId"`
	LearnerUserID   string `json:"learnerUserId"`
	SkillSlug       string `json:"skillSlug"`
	TopicCode       string `json:"topicCode"`
}

type ParticipantAgreementState struct {
	UserID           string     `json:"userId"`
	Accepted         bool       `json:"accepted"`
	AcceptedAt       *time.Time `json:"acceptedAt"`
	AgreementVersion string     `json:"agreementVersion"`
}

type SessionResponse struct {
	SessionID           string                      `json:"sessionId"`
	ProposalID          string                      `json:"proposalId"`
	Status              string                      `json:"status"`
	ScheduledStartAt    time.Time                   `json:"scheduledStartAt"`
	ScheduledEndAt      time.Time                   `json:"scheduledEndAt"`
	Blocks              []SessionBlock              `json:"blocks"`
	Agreements          []ParticipantAgreementState `json:"agreements"`
	RecommendedGuideIDs []string                    `json:"recommendedGuideIds"`
	LiveKitRoomName     string                      `json:"-"`
}

type CreateSessionRequest struct {
	ProposalID        string    `json:"proposalId"`
	ScheduledStartAt  time.Time `json:"scheduledStartAt"`
	ScheduledEndAt    time.Time `json:"scheduledEndAt"`
	AgreementAccepted bool      `json:"agreementAccepted"`
	AgreementVersion  string    `json:"agreementVersion"`
}

type AcceptAgreementRequest struct {
	AgreementAccepted bool   `json:"agreementAccepted"`
	AgreementVersion  string `json:"agreementVersion"`
}

type RescheduleSessionRequest struct {
	NewStartAt time.Time `json:"newStartAt"`
	NewEndAt   time.Time `json:"newEndAt"`
	Reason     string    `json:"reason"`
}

type CancelSessionRequest struct {
	Reason               string `json:"reason"`
	TechnicalOrEmergency bool   `json:"technicalOrEmergency"`
}

type RoomTokenResponse struct {
	SessionID     string         `json:"sessionId"`
	LiveKitURL    string         `json:"livekitUrl"`
	RoomName      string         `json:"roomName"`
	AccessToken   string         `json:"accessToken"`
	ExpiresAt     time.Time      `json:"expiresAt"`
	SessionStatus string         `json:"sessionStatus"`
	ActiveBlocks  []SessionBlock `json:"activeBlocks"`
}

type CloseSessionRequest struct {
	TerminalSessionState      string `json:"terminalSessionState"`
	TechnicalIncidentObserved bool   `json:"technicalIncidentObserved"`
	IncidentCode              string `json:"incidentCode"`
}

// Contratos HTTP con la API de Dominio en Java (Contratos 1 y 4)
type ParticipantValidation struct {
	UserID  string `json:"userId"`
	Teaches struct {
		SkillSlug   string `json:"skillSlug"`
		TopicCode   string `json:"topicCode"`
		IsQualified bool   `json:"isQualified"`
	} `json:"teaches"`
}

type ProposalValidation struct {
	ProposalID          string                  `json:"proposalId"`
	Status              string                  `json:"status"`
	ValidForScheduling  bool                    `json:"validForScheduling"`
	InvalidationReason  *string                 `json:"invalidationReason"`
	Participants        []ParticipantValidation `json:"participants"`
	RecommendedGuideIDs []string                `json:"recommendedGuideIds"`
}

type AttendanceFact struct {
	UserID             string     `json:"userId"`
	JoinedAt           *time.Time `json:"joinedAt"`
	LeftAt             *time.Time `json:"leftAt"`
	ConnectedSeconds   int        `json:"connectedSeconds"`
	DisconnectionCount int        `json:"disconnectionCount"`
	ObservedPresence   string     `json:"observedPresence"`
}

type SessionFactsPayload struct {
	FactReportID              string           `json:"factReportId"`
	SessionID                 string           `json:"sessionId"`
	ProposalID                string           `json:"proposalId"`
	TerminalSessionState      string           `json:"terminalSessionState"`
	OccurredAt                time.Time        `json:"occurredAt"`
	ParticipantsAttendance    []AttendanceFact `json:"participantsAttendance"`
	TechnicalIncidentObserved bool             `json:"technicalIncidentObserved"`
	IncidentCode              *string          `json:"incidentCode"`
}

type DomainClient interface {
	ValidateProposal(ctx context.Context, proposalID string) (*ProposalValidation, error)
	ReportSessionFacts(ctx context.Context, fact SessionFactsPayload) error
}

type HTTPDomainClient struct {
	BaseURL      string
	ServiceToken string
	TokenURL     string
	ClientID     string
	ClientSecret string
	HTTP         *http.Client

	mu          sync.Mutex
	cachedToken string
	tokenExpiry time.Time
}

func (c *HTTPDomainClient) bearerToken(ctx context.Context) (string, error) {
	if strings.TrimSpace(c.ServiceToken) != "" {
		return c.ServiceToken, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cachedToken != "" && time.Now().Add(30*time.Second).Before(c.tokenExpiry) {
		return c.cachedToken, nil
	}
	if strings.TrimSpace(c.TokenURL) == "" || strings.TrimSpace(c.ClientID) == "" {
		return "", newProblem(http.StatusInternalServerError, "M2M_NOT_CONFIGURED", "Auth M2M no configurada", "Configura OIDC_TOKEN_URI y credenciales del servicio Go.")
	}
	form := url.Values{
		"grant_type":    {"client_credentials"},
		"client_id":     {c.ClientID},
		"client_secret": {c.ClientSecret},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("keycloak M2M token request returned HTTP %d", resp.StatusCode)
	}
	var tokResp struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tokResp); err != nil {
		return "", err
	}
	if tokResp.AccessToken == "" {
		return "", errors.New("empty M2M access_token")
	}
	c.cachedToken = tokResp.AccessToken
	c.tokenExpiry = time.Now().Add(time.Duration(max(60, tokResp.ExpiresIn)) * time.Second)
	return c.cachedToken, nil
}

func (c *HTTPDomainClient) ValidateProposal(ctx context.Context, proposalID string) (*ProposalValidation, error) {
	tok, err := c.bearerToken(ctx)
	if err != nil {
		return nil, newProblem(http.StatusBadGateway, "M2M_AUTH_ERROR", "Error de autenticación M2M", err.Error())
	}
	targetURL := fmt.Sprintf("%s/internal/v1/exchange-proposals/%s/validation", strings.TrimRight(c.BaseURL, "/"), proposalID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, newProblem(http.StatusBadGateway, "DOMAIN_UNAVAILABLE", "Dominio no disponible", err.Error())
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, newProblem(http.StatusNotFound, "PROPOSAL_NOT_FOUND", "Propuesta no encontrada", "La propuesta indicada no existe en el dominio.")
	}
	if resp.StatusCode != http.StatusOK {
		return nil, newProblem(http.StatusBadGateway, "DOMAIN_VALIDATION_ERROR", "Error validando propuesta", fmt.Sprintf("Java respondió HTTP %d", resp.StatusCode))
	}
	var out ProposalValidation
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *HTTPDomainClient) ReportSessionFacts(ctx context.Context, fact SessionFactsPayload) error {
	tok, err := c.bearerToken(ctx)
	if err != nil {
		return err
	}
	raw, err := json.Marshal(fact)
	if err != nil {
		return err
	}
	targetURL := fmt.Sprintf("%s/internal/v1/exchanges/%s/session-facts", strings.TrimRight(c.BaseURL, "/"), fact.ProposalID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, targetURL, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", fact.FactReportID)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("domain fact ingestion returned status %d", resp.StatusCode)
	}
	return nil
}

type TokenIssuer interface {
	IssueRoomToken(roomName, userID string, now time.Time) (token string, expiresAt time.Time, err error)
}

type LiveKitTokenIssuer struct {
	APIKey    string
	APISecret string
}

func (l *LiveKitTokenIssuer) IssueRoomToken(roomName, userID string, now time.Time) (string, time.Time, error) {
	exp := now.UTC().Add(maxLiveKitTTL)
	claims := jwt.MapClaims{
		"iss": l.APIKey,
		"sub": userID,
		"nbf": now.UTC().Unix(),
		"exp": exp.Unix(),
		"video": map[string]any{
			"roomJoin":     true,
			"room":         roomName,
			"canPublish":   true,
			"canSubscribe": true,
		},
	}
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(l.APISecret))
	return signed, exp, err
}

type IdempotentRecord struct {
	RequestHash    string
	ResponseStatus int
	ResponseBody   []byte
}

type OutboxFact struct {
	FactReportID string
	SessionID    string
	ProposalID   string
	Payload      SessionFactsPayload
}

type SessionRepository interface {
	GetIdempotency(ctx context.Context, key, operation, actor string) (*IdempotentRecord, error)
	CreateSession(ctx context.Context, ses *SessionResponse, idemKey, reqHash, actor string) error
	GetSession(ctx context.Context, sessionID string) (*SessionResponse, error)
	UpdateAgreement(ctx context.Context, sessionID, userID, version string, accepted bool, at time.Time, newStatus string) (*SessionResponse, error)
	Reschedule(ctx context.Context, sessionID, actor, idemKey, reqHash string, start, end time.Time, partnerID string) (*SessionResponse, error)
	CancelAndEnqueueFact(ctx context.Context, sessionID, actor, reason string, techOrEmerg bool, fact OutboxFact) (*SessionResponse, error)
	TransitionToInProgressAndLogToken(ctx context.Context, sessionID, actor string, at time.Time) (*SessionResponse, error)
	CloseSessionAndEnqueueFact(ctx context.Context, sessionID, terminalState string, fact OutboxFact) (*SessionResponse, error)
	ListPendingOutboxFacts(ctx context.Context, limit int) ([]OutboxFact, error)
	MarkOutboxDelivered(ctx context.Context, factReportID string) error
	MarkOutboxFailed(ctx context.Context, factReportID, errMsg string) error
}

type SessionService struct {
	Repo       SessionRepository
	Domain     DomainClient
	LiveKit    TokenIssuer
	LiveKitURL string
	Clock      func() time.Time
}

func (s *SessionService) CreateSession(ctx context.Context, actor, idemKey string, req CreateSessionRequest) (*SessionResponse, bool, error) {
	if len(strings.TrimSpace(idemKey)) < 8 {
		return nil, false, newProblem(http.StatusUnprocessableEntity, "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key requerida", "Envía Idempotency-Key (mínimo 8 caracteres).")
	}
	reqHash := hashJSON(req)
	if cached, err := s.Repo.GetIdempotency(ctx, idemKey, "CREATE_SESSION", actor); err == nil && cached != nil {
		if cached.RequestHash != reqHash {
			return nil, false, newProblem(http.StatusConflict, "IDEMPOTENCY_CONFLICT", "Clave reutilizada", "La misma Idempotency-Key fue usada con otro payload.")
		}
		var out SessionResponse
		if err := json.Unmarshal(cached.ResponseBody, &out); err != nil {
			return nil, false, err
		}
		return &out, true, nil
	}

	now := s.Clock()
	if !req.AgreementAccepted || !req.ScheduledEndAt.After(req.ScheduledStartAt) || !req.ScheduledStartAt.After(now) {
		return nil, false, newProblem(http.StatusUnprocessableEntity, "INVALID_SESSION_REQUEST", "Datos inválidos", "Debes aceptar el acuerdo y definir un horario futuro válido.")
	}
	if strings.TrimSpace(req.AgreementVersion) == "" {
		req.AgreementVersion = "v1.0-pilot"
	}

	val, err := s.Domain.ValidateProposal(ctx, req.ProposalID)
	if err != nil {
		return nil, false, err
	}
	if !val.ValidForScheduling || len(val.Participants) != 2 {
		return nil, false, newProblem(http.StatusConflict, "PROPOSAL_INVALID_FOR_SCHEDULING", "Propuesta no habilitada", "La propuesta no está aceptada o una habilitación dejó de estar vigente.")
	}
	if val.Participants[0].UserID != actor && val.Participants[1].UserID != actor {
		return nil, false, newProblem(http.StatusForbidden, "NOT_PROPOSAL_PARTICIPANT", "Acceso denegado", "Solo los participantes de la propuesta pueden agendar la sesión.")
	}

	totalMins := int(req.ScheduledEndAt.Sub(req.ScheduledStartAt).Minutes())
	blockMins := max(10, min(90, totalMins/2))
	sessionID := uuid.NewString()
	p0, p1 := val.Participants[0], val.Participants[1]

	ses := &SessionResponse{
		SessionID:        sessionID,
		ProposalID:       req.ProposalID,
		Status:           StatusAwaitingAgreement,
		ScheduledStartAt: req.ScheduledStartAt.UTC(),
		ScheduledEndAt:   req.ScheduledEndAt.UTC(),
		LiveKitRoomName:  "kanjora_ses_" + sessionID,
		RecommendedGuideIDs: val.RecommendedGuideIDs,
		Blocks: []SessionBlock{
			{BlockOrder: 1, DurationMinutes: blockMins, TeacherUserID: p0.UserID, LearnerUserID: p1.UserID, SkillSlug: p0.Teaches.SkillSlug, TopicCode: p0.Teaches.TopicCode},
			{BlockOrder: 2, DurationMinutes: blockMins, TeacherUserID: p1.UserID, LearnerUserID: p0.UserID, SkillSlug: p1.Teaches.SkillSlug, TopicCode: p1.Teaches.TopicCode},
		},
		Agreements: []ParticipantAgreementState{
			makeAgreement(p0.UserID, actor, req.AgreementVersion, now),
			makeAgreement(p1.UserID, actor, req.AgreementVersion, now),
		},
	}
	if err := s.Repo.CreateSession(ctx, ses, idemKey, reqHash, actor); err != nil {
		return nil, false, err
	}
	return ses, false, nil
}

func (s *SessionService) GetSession(ctx context.Context, actor, sessionID string) (*SessionResponse, error) {
	ses, err := s.Repo.GetSession(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	if !isParticipant(ses, actor) {
		return nil, newProblem(http.StatusForbidden, "NOT_SESSION_PARTICIPANT", "Acceso denegado", "No perteneces a esta sesión.")
	}
	return ses, nil
}

func (s *SessionService) AcceptAgreement(ctx context.Context, actor, sessionID string, req AcceptAgreementRequest) (*SessionResponse, error) {
	if !req.AgreementAccepted {
		return nil, newProblem(http.StatusUnprocessableEntity, "AGREEMENT_REQUIRED", "Acuerdo requerido", "Debes aceptar las reglas de la sesión para continuar.")
	}
	ses, err := s.GetSession(ctx, actor, sessionID)
	if err != nil {
		return nil, err
	}
	if ses.Status != StatusAwaitingAgreement && ses.Status != StatusReschedulePending && ses.Status != StatusScheduled {
		return nil, newProblem(http.StatusConflict, "INVALID_SESSION_STATE", "Estado inválido", "La sesión está cancelada o ya finalizó.")
	}
	version := req.AgreementVersion
	if strings.TrimSpace(version) == "" {
		version = "v1.0-pilot"
	}
	otherAccepted := false
	for _, a := range ses.Agreements {
		if a.UserID != actor && a.Accepted && a.AgreementVersion == version {
			otherAccepted = true
		}
	}
	nextStatus := ses.Status
	if otherAccepted {
		nextStatus = StatusScheduled
	}
	return s.Repo.UpdateAgreement(ctx, sessionID, actor, version, true, s.Clock().UTC(), nextStatus)
}

func (s *SessionService) RescheduleSession(ctx context.Context, actor, sessionID, idemKey string, req RescheduleSessionRequest) (*SessionResponse, error) {
	if len(strings.TrimSpace(idemKey)) < 8 {
		return nil, newProblem(http.StatusUnprocessableEntity, "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key requerida", "Envía Idempotency-Key para reprogramar.")
	}
	reqHash := hashJSON(req)
	if cached, err := s.Repo.GetIdempotency(ctx, idemKey, "RESCHEDULE_SESSION", actor); err == nil && cached != nil {
		if cached.RequestHash != reqHash {
			return nil, newProblem(http.StatusConflict, "IDEMPOTENCY_CONFLICT", "Clave reutilizada", "La misma Idempotency-Key fue usada con otro horario de reprogramación.")
		}
		var out SessionResponse
		if err := json.Unmarshal(cached.ResponseBody, &out); err != nil {
			return nil, err
		}
		return &out, nil
	}
	ses, err := s.GetSession(ctx, actor, sessionID)
	if err != nil {
		return nil, err
	}
	if !slices.Contains([]string{StatusAwaitingAgreement, StatusScheduled, StatusReschedulePending}, ses.Status) {
		return nil, newProblem(http.StatusConflict, "CANNOT_RESCHEDULE", "No reprogramable", "No se puede reprogramar una sesión en curso o terminada.")
	}
	now := s.Clock().UTC()
	if !req.NewEndAt.After(req.NewStartAt) || !req.NewStartAt.After(now) {
		return nil, newProblem(http.StatusUnprocessableEntity, "INVALID_TIME_WINDOW", "Horario inválido", "El nuevo horario debe ser futuro y coherente.")
	}
	partnerID := partnerOf(ses, actor)
	return s.Repo.Reschedule(ctx, sessionID, actor, idemKey, reqHash, req.NewStartAt.UTC(), req.NewEndAt.UTC(), partnerID)
}

func (s *SessionService) CancelSession(ctx context.Context, actor, sessionID string, req CancelSessionRequest) (*SessionResponse, error) {
	if len(strings.TrimSpace(req.Reason)) < 3 {
		return nil, newProblem(http.StatusUnprocessableEntity, "INVALID_CANCEL_REASON", "Motivo requerido", "Indica el motivo de cancelación.")
	}
	ses, err := s.GetSession(ctx, actor, sessionID)
	if err != nil {
		return nil, err
	}
	if !slices.Contains([]string{StatusAwaitingAgreement, StatusScheduled, StatusReschedulePending}, ses.Status) {
		return nil, newProblem(http.StatusConflict, "CANNOT_CANCEL", "Estado inválido", "Solo se pueden cancelar sesiones antes de iniciar.")
	}
	now := s.Clock().UTC()
	factID := "fct_" + uuid.NewString()
	fact := OutboxFact{
		FactReportID: factID, SessionID: ses.SessionID, ProposalID: ses.ProposalID,
		Payload: SessionFactsPayload{
			FactReportID: factID, SessionID: ses.SessionID, ProposalID: ses.ProposalID,
			TerminalSessionState: "CANCELLED_BEFORE_START", OccurredAt: now,
			ParticipantsAttendance: []AttendanceFact{
				{UserID: ses.Agreements[0].UserID, ObservedPresence: "ABSENT"},
				{UserID: ses.Agreements[1].UserID, ObservedPresence: "ABSENT"},
			},
			TechnicalIncidentObserved: req.TechnicalOrEmergency,
		},
	}
	updated, err := s.Repo.CancelAndEnqueueFact(ctx, sessionID, actor, req.Reason, req.TechnicalOrEmergency, fact)
	if err != nil {
		return nil, err
	}
	s.tryDeliverFact(ctx, fact)
	return updated, nil
}

func (s *SessionService) IssueRoomToken(ctx context.Context, actor, sessionID string) (*RoomTokenResponse, error) {
	ses, err := s.GetSession(ctx, actor, sessionID)
	if err != nil {
		return nil, err
	}
	if (ses.Status != StatusScheduled && ses.Status != StatusInProgress) || !bothAccepted(ses) {
		return nil, newProblem(http.StatusConflict, "ROOM_ACCESS_DENIED_BY_STATE", "Sala no habilitada", "Ambos participantes deben aceptar el acuerdo y la sesión debe estar agendada.")
	}
	now := s.Clock().UTC()
	if now.Before(ses.ScheduledStartAt.Add(-earlyJoinWindow)) || now.After(ses.ScheduledEndAt) {
		return nil, newProblem(http.StatusUnprocessableEntity, "OUTSIDE_SESSION_WINDOW", "Fuera de horario", "La sala solo abre desde 10 minutos antes hasta el fin programado.")
	}
	updated, err := s.Repo.TransitionToInProgressAndLogToken(ctx, sessionID, actor, now)
	if err != nil {
		return nil, err
	}
	token, exp, err := s.LiveKit.IssueRoomToken(updated.LiveKitRoomName, actor, now)
	if err != nil {
		return nil, err
	}
	return &RoomTokenResponse{
		SessionID: updated.SessionID, LiveKitURL: s.LiveKitURL, RoomName: updated.LiveKitRoomName,
		AccessToken: token, ExpiresAt: exp, SessionStatus: updated.Status, ActiveBlocks: updated.Blocks,
	}, nil
}

func (s *SessionService) CloseAndReportFacts(ctx context.Context, actor, sessionID string, req CloseSessionRequest) (*SessionResponse, error) {
	ses, err := s.GetSession(ctx, actor, sessionID)
	if err != nil {
		return nil, err
	}
	if !slices.Contains([]string{StatusScheduled, StatusInProgress}, ses.Status) {
		return nil, newProblem(http.StatusConflict, "INVALID_CLOSE_STATE", "Estado inválido", "La sesión no está activa para cierre.")
	}
	allowed := []string{StatusCompletedObserved, StatusNoShowObserved, StatusAbandonedMidCall, StatusTechnicalFailure}
	if !slices.Contains(allowed, req.TerminalSessionState) {
		return nil, newProblem(http.StatusUnprocessableEntity, "INVALID_TERMINAL_STATE", "Estado terminal inválido", "Estado de cierre no soportado.")
	}
	now := s.Clock().UTC()
	factID := "fct_" + uuid.NewString()
	var inc *string
	if strings.TrimSpace(req.IncidentCode) != "" {
		c := strings.TrimSpace(req.IncidentCode)
		inc = &c
	}
	presence := "PRESENT_FULL"
	if req.TerminalSessionState == StatusNoShowObserved {
		presence = "ABSENT"
	}
	fact := OutboxFact{
		FactReportID: factID, SessionID: ses.SessionID, ProposalID: ses.ProposalID,
		Payload: SessionFactsPayload{
			FactReportID: factID, SessionID: ses.SessionID, ProposalID: ses.ProposalID,
			TerminalSessionState: req.TerminalSessionState, OccurredAt: now,
			ParticipantsAttendance: []AttendanceFact{
				{UserID: ses.Agreements[0].UserID, ObservedPresence: presence},
				{UserID: ses.Agreements[1].UserID, ObservedPresence: presence},
			},
			TechnicalIncidentObserved: req.TechnicalIncidentObserved, IncidentCode: inc,
		},
	}
	updated, err := s.Repo.CloseSessionAndEnqueueFact(ctx, sessionID, req.TerminalSessionState, fact)
	if err != nil {
		return nil, err
	}
	s.tryDeliverFact(ctx, fact)
	return updated, nil
}

func (s *SessionService) FlushOutbox(ctx context.Context) {
	items, err := s.Repo.ListPendingOutboxFacts(ctx, 20)
	if err != nil {
		return
	}
	for _, f := range items {
		s.tryDeliverFact(ctx, f)
	}
}

func (s *SessionService) tryDeliverFact(ctx context.Context, fact OutboxFact) {
	if s.Domain == nil {
		return
	}
	if err := s.Domain.ReportSessionFacts(ctx, fact.Payload); err != nil {
		_ = s.Repo.MarkOutboxFailed(ctx, fact.FactReportID, err.Error())
		return
	}
	_ = s.Repo.MarkOutboxDelivered(ctx, fact.FactReportID)
}

func makeAgreement(userID, actor, version string, now time.Time) ParticipantAgreementState {
	if userID == actor {
		t := now.UTC()
		return ParticipantAgreementState{UserID: userID, Accepted: true, AcceptedAt: &t, AgreementVersion: version}
	}
	return ParticipantAgreementState{UserID: userID, Accepted: false, AcceptedAt: nil, AgreementVersion: version}
}

func isParticipant(ses *SessionResponse, userID string) bool {
	for _, a := range ses.Agreements {
		if a.UserID == userID {
			return true
		}
	}
	return false
}

func bothAccepted(ses *SessionResponse) bool {
	return len(ses.Agreements) == 2 && ses.Agreements[0].Accepted && ses.Agreements[1].Accepted
}

func partnerOf(ses *SessionResponse, actor string) string {
	for _, a := range ses.Agreements {
		if a.UserID != actor {
			return a.UserID
		}
	}
	return ""
}

func hashJSON(v any) string {
	b, _ := json.Marshal(v)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func asProblem(err error) (*ProblemError, bool) {
	var p *ProblemError
	ok := errors.As(err, &p)
	return p, ok
}

