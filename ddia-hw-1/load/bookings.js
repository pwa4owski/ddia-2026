import http from 'k6/http';
import { check } from 'k6';
import execution from 'k6/execution';
import { Rate } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://app:8080').replace(/\/$/, '');
const targetRps = integerOption('LOAD_TARGET_RPS', 100, 1, 1000);
const errorPercent = integerOption('LOAD_ERROR_PERCENT', 15, 0, 40);
const unexpectedStatus = new Rate('unexpected_status');
const intentionalErrors = new Rate('intentional_http_errors');
const expectedResponses = {
  200: http.expectedStatuses(200),
  201: http.expectedStatuses(201),
  400: http.expectedStatuses(400),
  404: http.expectedStatuses(404),
  409: http.expectedStatuses(409),
};

export const options = {
  discardResponseBodies: true,
  scenarios: {
    background: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(10, targetRps),
      maxVUs: Math.max(20, targetRps * 2),
      stages: [
        { duration: __ENV.LOAD_RAMP_DURATION || '5m', target: targetRps },
        { duration: __ENV.LOAD_HOLD_DURATION || '10m', target: targetRps },
      ],
      gracefulStop: '5s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    unexpected_status: ['rate==0'],
    http_req_failed: ['rate==0'],
    dropped_iterations: ['count==0'],
  },
};

export function setup() {
  const runId = `${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
  const date = new Date().toISOString().slice(0, 10);
  const bookings = [];
  for (let index = 0; index < 10; index++) {
    const booking = { room: `load-seed-${runId}-${index}`, date, hour: 14 };
    const response = http.post(`${baseUrl}/bookings`, JSON.stringify(booking), {
      ...params(201, 'POST /bookings · setup'), responseType: 'text',
    });
    if (!check(response, { 'seed booking created': (result) => result.status === 201 })) {
      throw new Error(`Cannot prepare bookings: HTTP ${response.status}`);
    }
    bookings.push({ ...booking, id: response.json('id') });
  }
  console.info(`Background load: 1 -> ${targetRps} requests/s; planned errors ${errorPercent}%.`);
  return { runId, date, bookings };
}

export default function (data) {
  const iteration = execution.scenario.iterationInTest;
  // Multiplication permutes each block of 100, spreading errors across the run.
  const slot = (iteration * 37) % 100;
  const booking = data.bookings[iteration % data.bookings.length];
  const other = data.bookings[(iteration + 1) % data.bookings.length];
  let response;
  let expected;

  if (slot < 100 - errorPercent) {
    const operation = slot % 17;
    if (operation < 12) {
      expected = 200;
      response = http.get(`${baseUrl}/bookings/${booking.id}`, params(expected, 'GET /bookings/{id}'));
    } else if (operation < 16) {
      expected = 200;
      response = http.put(`${baseUrl}/bookings/${booking.id}`, payload(booking), params(expected, 'PUT /bookings/{id}'));
    } else {
      expected = 201;
      response = http.post(`${baseUrl}/bookings`, JSON.stringify({
        room: `load-new-${data.runId}-${iteration}`, date: data.date, hour: 14,
      }), params(expected, 'POST /bookings'));
    }
  } else {
    const error = (slot - (100 - errorPercent)) % 3;
    if (error === 0) {
      expected = 400;
      response = invalidRequest(iteration, booking);
    } else if (error === 1) {
      expected = 404;
      const path = `${baseUrl}/bookings/9223372036854775807`;
      response = iteration % 2 === 0
        ? http.get(path, params(expected, 'GET /bookings/{id} · missing'))
        : http.put(path, payload(booking), params(expected, 'PUT /bookings/{id} · missing'));
    } else {
      expected = 409;
      response = iteration % 2 === 0
        ? http.post(`${baseUrl}/bookings`, payload(other), params(expected, 'POST /bookings · occupied'))
        : http.put(`${baseUrl}/bookings/${booking.id}`, payload(other), params(expected, 'PUT /bookings/{id} · occupied'));
    }
  }

  check(response, { 'expected HTTP status': (result) => result.status === expected });
  unexpectedStatus.add(response.status !== expected);
  intentionalErrors.add(expected >= 400 && response.status === expected);
}

function invalidRequest(iteration, booking) {
  const invalidBodies = [
    { ...booking, hour: 24 },
    { ...booking, hour: -1 },
    { ...booking, room: '' },
    { ...booking, room: 'x'.repeat(101) },
    { ...booking, date: 'not-a-date' },
    { room: booking.room },
    '{broken',
  ];
  const body = invalidBodies[Math.floor(iteration / 100) % invalidBodies.length];
  const encoded = typeof body === 'string' ? body : JSON.stringify(body);
  return iteration % 2 === 0
    ? http.post(`${baseUrl}/bookings`, encoded, params(400, 'POST /bookings · invalid'))
    : http.put(`${baseUrl}/bookings/${booking.id}`, encoded, params(400, 'PUT /bookings/{id} · invalid'));
}

function payload(booking) {
  return JSON.stringify({ room: booking.room, date: booking.date, hour: booking.hour });
}

function params(expected, name) {
  return {
    headers: { 'Content-Type': 'application/json' },
    timeout: '3s',
    redirects: 0,
    tags: { name, expected_status: String(expected) },
    responseCallback: expectedResponses[expected],
  };
}

function integerOption(name, fallback, min, max) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}
