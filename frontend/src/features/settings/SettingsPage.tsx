import { PageHeader } from '@/components/ui/page-header'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useMe } from '@/features/auth/api'
import { useSearchParams } from 'react-router'
import { AdminSettings } from './AdminSettings'
import { DisplaySettings } from './DisplaySettings'
import { SecuritySettings } from './SecuritySettings'

export function SettingsPage() {
  const me = useMe()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'general'
  return (
    <>
      <PageHeader title="تنظیمات" />
      <Tabs value={tab} onValueChange={(v) => setParams({ tab: v }, { replace: true })}>
        <TabsList className="w-full sm:w-auto">
          <TabsTrigger value="general">عمومی</TabsTrigger>
          <TabsTrigger value="security">حساب و امنیت</TabsTrigger>
          {me.role === 'ADMIN' ? <TabsTrigger value="admin">مدیریت</TabsTrigger> : null}
        </TabsList>
        <TabsContent value="general"><DisplaySettings /></TabsContent>
        <TabsContent value="security"><SecuritySettings /></TabsContent>
        {me.role === 'ADMIN' ? <TabsContent value="admin"><AdminSettings /></TabsContent> : null}
      </Tabs>
    </>
  )
}
