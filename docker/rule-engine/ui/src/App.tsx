import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAdmin, RequireAuth } from './auth/guards';
import { Shell } from './components/Shell';
import { ChannelsPage } from './pages/Channels';
import { GroupFormPage } from './pages/GroupForm';
import { GroupsPage } from './pages/Groups';
import { LibraryPage } from './pages/Library';
import { LoginPage } from './pages/Login';
import { AuditPage, ChatLogPage, DashboardPage, EvaluationsPage, LogsLayout } from './pages/Logs';
import { OverviewPage } from './pages/Overview';
import { RuleFormPage } from './pages/RuleForm';
import { RulesPage } from './pages/Rules';
import { TestBenchPage } from './pages/TestBench';

/** The route table. Everything but the login page needs a session; the logs also need the administrator role. */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Shell />
          </RequireAuth>
        }
      >
        <Route index element={<OverviewPage />} />
        <Route path="library" element={<LibraryPage />} />
        <Route path="rules" element={<RulesPage />} />
        <Route path="rules/new" element={<RuleFormPage />} />
        <Route path="groups" element={<GroupsPage />} />
        <Route path="groups/new" element={<GroupFormPage />} />
        <Route path="groups/:id/edit" element={<GroupFormPage />} />
        <Route path="channels" element={<ChannelsPage />} />
        <Route path="test" element={<TestBenchPage />} />
        <Route
          path="logs"
          element={
            <RequireAdmin>
              <LogsLayout />
            </RequireAdmin>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="evaluations" element={<EvaluationsPage />} />
          <Route path="audit" element={<AuditPage />} />
          <Route path="chat" element={<ChatLogPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
